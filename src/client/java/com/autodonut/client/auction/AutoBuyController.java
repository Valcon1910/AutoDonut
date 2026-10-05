package com.autodonut.client.auction;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import com.autodonut.client.Compat;
import com.autodonut.client.Lockdown;
import com.autodonut.client.ServerContext;
import com.autodonut.client.config.AutoDonutConfig;
import com.autodonut.client.config.BuyRule;
import com.autodonut.client.config.PriceFormat;
import com.autodonut.client.ui.AutoDonutScreen;

/**
 * Checks /ah for listings that match a buy rule and fit its budget, one step at a time:
 * wait -> send /ah (or /ah name) -> wait for the menu -> scan (and page in Browse mode)
 * -> click the listing -> confirm -> wait the rule's delay.
 */
public final class AutoBuyController {
	private static final AutoBuyController INSTANCE = new AutoBuyController();
	/** A step running longer than this is abandoned and the menu closed. */
	private static final long STEP_TIMEOUT_MS = 15_000;
	/** How long the auction menu may take to open after the command. */
	private static final long OPEN_WAIT_MS = 5000;
	/** How long to wait for a confirm menu (or a purchase message) after clicking a listing. */
	private static final long CONFIRM_WAIT_MS = 3500;
	/** How long to wait for the server's answer after confirming. */
	private static final long RESULT_WAIT_MS = 3500;
	private static final long NO_MONEY_PAUSE_MS = 5L * 60L * 1000L;
	/** Pages flipped through in Browse mode before giving up for this check. */
	private static final int MAX_PAGES = 4;
	private static final String[] SUCCESS_WORDS = {"you bought", "you purchased", "purchased", "successfully bought"};
	private static final String[] NO_MONEY_WORDS = {"not enough", "can't afford", "cannot afford", "insufficient", "don't have enough"};
	private static final String[] GONE_WORDS = {"already sold", "no longer", "not available", "doesn't exist", "does not exist"};

	private enum Phase { IDLE, OPENING, SCANNING, PAGING, AWAIT_CONFIRM, CONFIRMING, RESULT }

	private final Humanizer humanizer = new Humanizer(new Random());
	private final Map<BuyRule, Long> nextCheckAt = new IdentityHashMap<>();
	private final Map<BuyRule, Long> rulePausedUntil = new IdentityHashMap<>();
	private final Map<BuyRule, Integer> searchCursor = new IdentityHashMap<>();

	private Phase phase = Phase.IDLE;
	private long phaseStartedAt;
	private long phaseUntil;
	private long lastTickAt;
	private long warmupUntil;
	private BuyRule rule;
	private int ruleCursor;
	/** The auction menu Auto Buy opened, and the confirm menu it is answering. */
	private Screen ahScreen;
	private Screen confirmScreen;
	/**
	 * Menu the server opened for Auto Buy that was never shown ("phantom"): its screen was kept
	 * from becoming current, so the player keeps moving and looking around while
	 * {@code player.containerMenu} still receives the slots and accepts clicks.
	 */
	private Screen phantom;
	/** Set when the player opened a screen of their own mid-check; the check ends next tick. */
	private boolean playerTookOver;
	private int pages;
	/** Whether this check's command searched for an item name (otherwise plain /ah). */
	private boolean searched;
	/** Snapshot of the menu contents before turning a page, to notice the new page. */
	private String pageSignature = "";
	private String boughtItemId = "";
	private long boughtPrice;
	private boolean serverBought;
	private boolean serverRefused;
	/** The server said the listing was already gone. */
	private boolean gone;
	/** Paused with the pause key (rules with the key option turned on wait). */
	private boolean keyPaused;
	private int boughtThisSession;
	private String status = "Disabled";

	public static AutoBuyController get() {
		return INSTANCE;
	}

	public String status() {
		return status;
	}

	public int boughtThisSession() {
		return boughtThisSession;
	}

	/** True while a check or purchase is in progress (the auction menu is Auto Buy's). */
	public boolean busy() {
		return phase != Phase.IDLE;
	}

	public boolean keyPaused() {
		return keyPaused;
	}

	public boolean isActive() {
		AutoDonutConfig cfg = AutoDonutConfig.get();
		return cfg.autoBuyEnabled && !Lockdown.active() && (!cfg.onlyOnDonut || ServerContext.isOnDonut());
	}

	/**
	 * The menu Auto Buy is working in: the phantom while there is one (dropped once the server
	 * closes or replaces its menu), otherwise the current screen.
	 */
	private Screen view(Minecraft mc) {
		if (phantom != null) {
			LocalPlayer player = mc.player;
			if (player == null || !(phantom instanceof AbstractContainerScreen<?> c) || c.getMenu() != player.containerMenu) {
				phantom = null;
			}
		}
		return phantom != null ? phantom : mc.gui.screen();
	}

	/** Whether Auto Buy has a phantom menu open right now. */
	public boolean hasPhantom() {
		return phantom != null;
	}

	/**
	 * Called at the start of every set-screen (see GuiMixin). Returns true to keep the screen from
	 * opening: a server menu Auto Buy is waiting for becomes its phantom instead. Any other screen
	 * opening while a phantom is up means the player took over: the phantom container is closed
	 * first, then their screen opens normally.
	 */
	public boolean onSetScreen(Screen screen) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (screen instanceof AbstractContainerScreen<?> container && player != null && busy()
				&& AutoDonutConfig.get().confirmInBackground
				&& container.getMenu() == player.containerMenu && player.containerMenu != player.inventoryMenu) {
			phantom = screen;
			return true;
		}
		if (screen != null && phantom != null) {
			playerTookOver = true;
			closePhantom(player);
		}
		return false;
	}

	/** Tells the server the phantom menu is closed and forgets it. */
	private void closePhantom(LocalPlayer player) {
		Screen p = phantom;
		phantom = null;
		if (player != null && p instanceof AbstractContainerScreen<?> c && c.getMenu() == player.containerMenu
				&& player.containerMenu != player.inventoryMenu) {
			player.closeContainer();
		}
	}

	/**
	 * True while this screen is a menu Auto Buy is working in (the /ah menu, its pages and the
	 * confirm menu) and "menus in background" is on: it isn't drawn. Right after the command and
	 * after clicking a listing, a newly opened chest menu is hidden too so it never flashes up.
	 */
	public boolean isHidden(Screen screen) {
		if (screen == null || !busy() || !AutoDonutConfig.get().confirmInBackground) return false;
		if (screen == ahScreen || screen == confirmScreen) return true;
		if (phase != Phase.OPENING && phase != Phase.AWAIT_CONFIRM && phase != Phase.PAGING) return false;
		LocalPlayer player = Minecraft.getInstance().player;
		return screen instanceof AbstractContainerScreen<?> && player != null && player.containerMenu != player.inventoryMenu;
	}

	/** Whether this screen is a menu Auto Buy opened (the pause key also works there). */
	public boolean ownsScreen(Screen screen) {
		return screen != null && busy() && (screen == ahScreen || screen == confirmScreen);
	}

	/**
	 * Pause key: pauses Auto Buy (rules with the key option), or resumes it, also clearing the
	 * purchase counters of rules that reached their "pause after" limit.
	 */
	public void togglePause() {
		AutoDonutConfig cfg = AutoDonutConfig.get();
		LocalPlayer player = Minecraft.getInstance().player;
		boolean limited = cfg.buyRules.stream().anyMatch(BuyRule::limitReached);
		if (keyPaused || limited) {
			keyPaused = false;
			for (BuyRule r : cfg.buyRules) {
				if (r.limitReached()) r.purchases = 0;
			}
			status = "Resumed";
			if (player != null) notifyPlayer(player, "Auto Buy resumed.");
		} else {
			if (cfg.buyRules.stream().noneMatch(r -> r.pauseKeyEnabled)) {
				if (player != null) notifyPlayer(player, "No Auto Buy item uses the pause key (turn it on in the item's settings).");
				return;
			}
			keyPaused = true;
			if (busy()) abort(Minecraft.getInstance());
			status = "Paused (key)";
			if (player != null) notifyPlayer(player, "Auto Buy paused. Press the key again to resume.");
		}
		com.autodonut.client.ui.UiSounds.click();
	}

	public void reset() {
		closePhantom(Minecraft.getInstance().player);
		playerTookOver = false;
		setPhase(Phase.IDLE);
		ahScreen = null;
		confirmScreen = null;
		rule = null;
		pages = 0;
	}

	public void onJoin() {
		warmupUntil = System.currentTimeMillis() + 10_000;
		lastTickAt = 0;
	}

	public void onDisconnect() {
		reset();
		nextCheckAt.clear();
		rulePausedUntil.clear();
	}

	private void setPhase(Phase next) {
		phase = next;
		phaseStartedAt = System.currentTimeMillis();
	}

	/** Ends the current check, closing a menu Auto Buy opened if it's still showing. */
	private void abort(Minecraft mc) {
		LocalPlayer player = mc.player;
		Screen open = mc.gui.screen();
		if (player != null && open != null && phantom == null && (open == ahScreen || open == confirmScreen)) {
			if (open instanceof AbstractContainerScreen<?>) player.closeContainer();
			else open.onClose();
		}
		reset();
	}

	public void tick(Minecraft mc) {
		long now = System.currentTimeMillis();
		AutoDonutConfig cfg = AutoDonutConfig.get();
		LocalPlayer player = mc.player;
		if (player == null || mc.gameMode == null) {
			reset();
			lastTickAt = 0;
			status = "Not in a world";
			return;
		}
		long sinceLastTick = lastTickAt == 0 ? 0 : Math.min(1000, now - lastTickAt);
		lastTickAt = now;
		AutoAuctionController auction = AutoAuctionController.get();

		if (Lockdown.active()) {
			if (busy()) abort(mc);
			status = "Offline (safety lockdown)";
			return;
		}
		if (com.autodonut.client.ui.BootOverlay.booting()) {
			status = "Booting up";
			return;
		}
		if (!cfg.autoBuyEnabled) {
			if (busy()) abort(mc);
			status = "Disabled";
			return;
		}
		if (cfg.onlyOnDonut && !ServerContext.isOnDonut()) {
			if (busy()) abort(mc);
			status = "Waiting for Donut SMP";
			return;
		}
		if (cfg.buyRules.stream().noneMatch(BuyRule::isComplete)) {
			if (busy()) abort(mc);
			status = "No items set up";
			return;
		}
		if (now < warmupUntil) {
			status = "Starting up";
			return;
		}
		if (auction.inCombat()) {
			if (busy()) abort(mc);
			freeze(sinceLastTick);
			status = "Waiting until combat ends";
			return;
		}
		if (auction.lagging() || !com.autodonut.client.ServerProbe.responding()) {
			// Nothing is clicked while lagging; the step watchdog and timers wait too.
			freeze(sinceLastTick);
			status = "Server not responding (Lag)";
			return;
		}
		if (phase != Phase.IDLE && now - phaseStartedAt > STEP_TIMEOUT_MS) {
			abort(mc);
			if (rule != null) nextCheckAt.put(rule, now + humanizer.between(3000, 6000));
			status = "Retrying shortly";
			return;
		}

		if (playerTookOver && phase != Phase.IDLE) {
			BuyRule current = rule;
			reset();
			if (current != null) nextCheckAt.put(current, now + humanizer.between(current.speed.minMs, current.speed.maxMs));
			status = "Stopped (you opened a menu)";
			return;
		}
		Screen open = view(mc);
		if (phase == Phase.IDLE) {
			if (auction.busy()) {
				freeze(sinceLastTick);
				status = "Waiting for Auto Auction";
				return;
			}
			if (open instanceof AutoDonutScreen) {
				freeze(sinceLastTick);
				status = "Waits until you close AutoDonut";
				return;
			}
			if (open != null) {
				freeze(sinceLastTick);
				status = "Frozen while a menu is open";
				return;
			}
			tickIdle(player, cfg, now);
			return;
		}
		// Mid-check: the player closed the menu or opened something else.
		boolean ours = open != null && (open == ahScreen || open == confirmScreen);
		boolean serverMenu = open instanceof AbstractContainerScreen<?> && player.containerMenu != player.inventoryMenu;
		boolean confirmDialog = phase == Phase.AWAIT_CONFIRM && open != null && looksLikeConfirm(open);
		if (open != null && !ours && !serverMenu && !confirmDialog) {
			// The player opened their own screen (chat, the panel, ...): give way instead of fighting them.
			BuyRule current = rule;
			abort(mc);
			if (current != null) nextCheckAt.put(current, now + humanizer.between(current.speed.minMs, current.speed.maxMs));
			status = "Stopped (you opened a menu)";
			return;
		}
		if (phase != Phase.OPENING && phase != Phase.AWAIT_CONFIRM && phase != Phase.RESULT && !ours) {
			reset();
			status = "Stopped (menu closed)";
			return;
		}
		switch (phase) {
			case OPENING -> tickOpening(mc, player, now);
			case SCANNING -> tickScanning(mc, player, now);
			case PAGING -> tickPaging(mc, now);
			case AWAIT_CONFIRM -> tickAwaitConfirm(mc, now);
			case CONFIRMING -> tickConfirming(mc, player, now);
			case RESULT -> tickResult(mc, player, cfg, now);
			default -> { }
		}
	}

	/** Timers stand still: due checks wait and the current step's watchdog is held back. */
	private void freeze(long sinceLastTick) {
		phaseStartedAt += sinceLastTick;
		phaseUntil += sinceLastTick;
		for (Map.Entry<BuyRule, Long> e : nextCheckAt.entrySet()) e.setValue(e.getValue() + sinceLastTick);
	}

	/** Whether a rule may be checked now (on, set up, not paused by limit, key or the server). */
	private boolean usable(BuyRule r, long now) {
		if (!r.enabled || !r.isComplete() || r.limitReached()) return false;
		if (keyPaused && r.pauseKeyEnabled) return false;
		Long until = rulePausedUntil.get(r);
		return until == null || now >= until;
	}

	private void tickIdle(LocalPlayer player, AutoDonutConfig cfg, long now) {
		List<BuyRule> rules = cfg.buyRules;
		long soonest = Long.MAX_VALUE;
		BuyRule due = null;
		int n = rules.size();
		// Round robin so every item gets its turn.
		for (int k = 0; k < n; k++) {
			BuyRule r = rules.get((ruleCursor + k) % n);
			if (!usable(r, now)) continue;
			long at = nextCheckAt.computeIfAbsent(r, x -> now + humanizer.between(x.speed.minMs, x.speed.maxMs));
			if (at <= now) {
				due = r;
				ruleCursor = (ruleCursor + k + 1) % n;
				break;
			}
			soonest = Math.min(soonest, at);
		}
		if (due == null) {
			if (soonest != Long.MAX_VALUE) {
				status = "Next check in " + seconds(soonest - now);
			} else if (keyPaused) {
				status = "Paused (" + com.autodonut.client.AutoDonutClient.buyPauseKeyName() + ")";
			} else if (rules.stream().anyMatch(BuyRule::limitReached)) {
				status = "Limit reached, " + com.autodonut.client.AutoDonutClient.buyPauseKeyName() + " resumes";
			} else {
				status = "Paused";
			}
			return;
		}
		rule = due;
		pages = 0;
		String command = "ah";
		searched = false;
		if (due.searchMode == BuyRule.SearchMode.SEARCH) {
			// Rotate through the plain items; a #tag can't be searched, so it browses instead.
			int cursor = searchCursor.merge(due, 1, Integer::sum) - 1;
			String entry = due.items.get(Math.floorMod(cursor, due.items.size()));
			ItemIndex.Entry e = entry.startsWith("#") ? null : ItemIndex.byId(entry);
			if (e != null) {
				command = "ah " + e.name();
				searched = true;
			}
		}
		player.connection.sendCommand(command);
		setPhase(Phase.OPENING);
		phaseUntil = now + OPEN_WAIT_MS;
		status = "Checking /" + command;
	}

	private void tickOpening(Minecraft mc, LocalPlayer player, long now) {
		Screen open = view(mc);
		if (open instanceof AbstractContainerScreen<?> && player.containerMenu != player.inventoryMenu) {
			ahScreen = open;
			setPhase(Phase.SCANNING);
			// Give the listings a moment to arrive and "look" at them like a person would.
			phaseUntil = now + humanizer.between(350, 800);
			return;
		}
		if (open != null && !(open instanceof ChatScreen)) {
			// Something else opened instead of the auction menu; leave it alone.
			reset();
			status = "Stopped (another menu opened)";
			return;
		}
		if (now > phaseUntil) {
			nextCheckAt.put(rule, now + humanizer.between(rule.speed.minMs, rule.speed.maxMs) + 2000);
			reset();
			status = "/ah didn't open";
		}
	}

	private void tickScanning(Minecraft mc, LocalPlayer player, long now) {
		if (now < phaseUntil) return;
		if (!(ahScreen instanceof AbstractContainerScreen<?> container)) {
			reset();
			return;
		}
		AbstractContainerMenu menu = container.getMenu();
		int best = -1;
		long bestPrice = Long.MAX_VALUE;
		for (int i = 0; i < menu.slots.size(); i++) {
			Slot slot = menu.slots.get(i);
			if (slot.container instanceof Inventory) continue;
			ItemStack stack = slot.getItem();
			if (stack.isEmpty()) continue;
			if (!rule.itemMatches(ItemIndex.idOf(stack.getItem()), ItemIndex.tagsOf(stack))) continue;
			if (!rule.allowsCount(stack.getCount())) continue;
			long price = priceOf(stack);
			if (price > 0 && rule.allows(price) && price < bestPrice) {
				best = i;
				bestPrice = price;
			}
		}
		if (best >= 0) {
			ItemStack stack = menu.slots.get(best).getItem();
			boughtItemId = ItemIndex.idOf(stack.getItem());
			boughtPrice = bestPrice;
			serverBought = false;
			serverRefused = false;
			gone = false;
			pageSignature = signature(menu);
			if (!InventoryActions.leftClick(mc, menu.containerId, best)) {
				abort(mc);
				status = "Couldn't click the listing";
				return;
			}
			setPhase(Phase.AWAIT_CONFIRM);
			phaseUntil = now + CONFIRM_WAIT_MS;
			status = "Buying " + stack.getHoverName().getString() + " for $" + PriceFormat.format(bestPrice);
			return;
		}
		boolean browse = !searched;
		int next = browse && pages < MAX_PAGES - 1 ? findNextPageSlot(menu) : -1;
		if (next >= 0 && InventoryActions.leftClick(mc, menu.containerId, next)) {
			pages++;
			pageSignature = signature(menu);
			setPhase(Phase.PAGING);
			phaseUntil = now + 2500;
			status = "Checking page " + (pages + 1);
			return;
		}
		// Nothing to buy right now: close the menu and wait for the next check.
		player.closeContainer();
		nextCheckAt.put(rule, now + humanizer.between(rule.speed.minMs, rule.speed.maxMs));
		reset();
		status = "No match, waiting";
	}

	private void tickPaging(Minecraft mc, long now) {
		Screen open = view(mc);
		if (open instanceof AbstractContainerScreen<?> container) {
			// The next page may come as a new menu or as new contents of the same one.
			boolean changed = open != ahScreen || !signature(container.getMenu()).equals(pageSignature);
			if (changed) {
				ahScreen = open;
				setPhase(Phase.SCANNING);
				phaseUntil = now + humanizer.between(300, 700);
				return;
			}
		}
		if (now > phaseUntil) {
			// The page didn't change: scan what's there one last time, without paging further.
			pages = MAX_PAGES;
			setPhase(Phase.SCANNING);
			phaseUntil = now;
		}
	}

	private void tickAwaitConfirm(Minecraft mc, long now) {
		if (serverBought || serverRefused) {
			setPhase(Phase.RESULT);
			phaseUntil = now;
			return;
		}
		Screen open = view(mc);
		if (open != null && open != ahScreen && !(open instanceof ChatScreen) && !(open instanceof AutoDonutScreen) && looksLikeConfirm(open)) {
			confirmScreen = open;
			setPhase(Phase.CONFIRMING);
			phaseUntil = now + Math.round(humanizer.between(250, 700) * AutoDonutConfig.get().speedFactor());
			status = "Confirming purchase";
			return;
		}
		if (open == ahScreen && open instanceof AbstractContainerScreen<?> container && findConfirmSlot(container.getMenu()) >= 0
				&& !signature(container.getMenu()).equals(pageSignature) && now - phaseStartedAt > 400) {
			// Same window, new contents: the confirm buttons were put into the auction menu.
			confirmScreen = open;
			setPhase(Phase.CONFIRMING);
			phaseUntil = now + Math.round(humanizer.between(250, 700) * AutoDonutConfig.get().speedFactor());
			status = "Confirming purchase";
			return;
		}
		if (now > phaseUntil) {
			// No prompt and no message: the listing was probably gone. Try again soon.
			abort(mc);
			nextCheckAt.put(rule, now + humanizer.between(rule.speed.minMs, rule.speed.maxMs));
			status = "Listing gone, waiting";
		}
	}

	private void tickConfirming(Minecraft mc, LocalPlayer player, long now) {
		if (now < phaseUntil) return;
		Screen open = view(mc);
		if (open == null || open != confirmScreen) {
			setPhase(Phase.RESULT);
			phaseUntil = now + RESULT_WAIT_MS;
			return;
		}
		boolean clicked = false;
		if (open instanceof AbstractContainerScreen<?> container) {
			AbstractContainerMenu menu = container.getMenu();
			int slot = findConfirmSlot(menu);
			clicked = slot >= 0 && InventoryActions.leftClick(mc, menu.containerId, slot);
		} else {
			Button button = AutoAuctionController.findConfirmButton(open);
			if (button != null) {
				button.onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
				clicked = true;
			}
		}
		if (!clicked) {
			notifyPlayer(player, "Couldn't find the confirm button for Auto Buy, confirm it yourself if you want it.");
			// Leave the menu to the player.
			nextCheckAt.put(rule, now + humanizer.between(rule.speed.minMs, rule.speed.maxMs) + 5000);
			reset();
			status = "Confirm it yourself";
			return;
		}
		setPhase(Phase.RESULT);
		phaseUntil = now + RESULT_WAIT_MS;
		status = "Confirmed, waiting";
	}

	private void tickResult(Minecraft mc, LocalPlayer player, AutoDonutConfig cfg, long now) {
		if (!serverBought && !serverRefused && now < phaseUntil) return;
		BuyRule r = rule;
		abort(mc);
		if (gone && !serverBought) {
			nextCheckAt.put(r, now + humanizer.between(r.speed.minMs, r.speed.maxMs));
			status = "Listing gone, waiting";
			return;
		}
		if (serverRefused) {
			// onGameMessage already paused the rule and told the player.
			status = "Can't afford it";
			return;
		}
		// Counted unless the server refused: a missed message never lets it buy past the limit.
		r.purchases++;
		boughtThisSession++;
		// After a purchase: the Safety page's min/max delay, never quicker than the rule's speed.
		long delay = humanizer.between(cfg.minDelaySeconds * 1000L, cfg.maxDelaySeconds * 1000L);
		nextCheckAt.put(r, now + Math.max(delay, humanizer.between(r.speed.minMs, r.speed.maxMs)));
		if (r.limitReached()) {
			status = "Bought " + r.purchases + " / " + r.pauseAfterCount + ", paused";
			notifyPlayer(player, "Auto Buy bought " + r.purchases + " "
					+ ItemIndex.entryFor(r.items.get(0)).name() + ", pausing that item. Press "
					+ com.autodonut.client.AutoDonutClient.buyPauseKeyName() + " to resume.");
		} else if (r.pauseAfterEnabled) {
			status = "Bought " + r.purchases + " / " + r.pauseAfterCount;
		} else {
			status = "Bought for $" + PriceFormat.format(boughtPrice);
		}
	}

	/** Called for every system message, like {@link AutoAuctionController#onGameMessage}. */
	public void onGameMessage(Component message, boolean overlay) {
		if (overlay || phase == Phase.IDLE || rule == null) return;
		String lower = message.getString().toLowerCase(Locale.ROOT);
		for (String w : NO_MONEY_WORDS) {
			if (lower.contains(w)) {
				serverRefused = true;
				rulePausedUntil.put(rule, System.currentTimeMillis() + NO_MONEY_PAUSE_MS);
				LocalPlayer player = Minecraft.getInstance().player;
				if (player != null) notifyPlayer(player, "Not enough money for Auto Buy, pausing that item for 5 minutes.");
				return;
			}
		}
		for (String w : GONE_WORDS) {
			if (lower.contains(w)) {
				// Someone else was faster; nothing was bought. Ends the wait right away.
				if (phase == Phase.AWAIT_CONFIRM || phase == Phase.RESULT) {
					gone = true;
					phaseUntil = System.currentTimeMillis();
				}
				return;
			}
		}
		for (String w : SUCCESS_WORDS) {
			if (lower.contains(w)) {
				serverBought = true;
				return;
			}
		}
	}

	/** Whole-listing price from the item's lore: the first amount on a line with "$" or "price". */
	static long priceOf(ItemStack stack) {
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore == null) return -1;
		for (Component line : lore.lines()) {
			String text = line.getString();
			String lower = text.toLowerCase(Locale.ROOT);
			if (!text.contains("$") && !lower.contains("price")) continue;
			long price = PriceFormat.parseFirst(text.contains("$") ? text.substring(text.indexOf('$')) : text);
			if (price > 0) return price;
		}
		return -1;
	}

	/** The "next page" button: a container item whose name mentions "next" (usually an arrow). */
	private static int findNextPageSlot(AbstractContainerMenu menu) {
		for (int i = 0; i < menu.slots.size(); i++) {
			Slot slot = menu.slots.get(i);
			if (slot.container instanceof Inventory) continue;
			ItemStack stack = slot.getItem();
			if (stack.isEmpty()) continue;
			String name = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
			if (name.contains("next")) return i;
		}
		return -1;
	}

	/** Item ids and counts of the container's own slots, to tell when its contents change. */
	private static String signature(AbstractContainerMenu menu) {
		StringBuilder sb = new StringBuilder();
		for (Slot slot : menu.slots) {
			if (slot.container instanceof Inventory) continue;
			ItemStack stack = slot.getItem();
			sb.append(stack.isEmpty() ? "-" : ItemIndex.idOf(stack.getItem()) + stack.getCount() + stack.getHoverName().getString()).append(';');
		}
		return sb.toString();
	}

	/**
	 * The confirm button of a buy menu: the last slot named confirm / buy / purchase / yes,
	 * otherwise the last lime or green item. Player inventory slots are ignored.
	 */
	private static int findConfirmSlot(AbstractContainerMenu menu) {
		int byName = -1;
		int byColour = -1;
		for (int i = 0; i < menu.slots.size(); i++) {
			Slot slot = menu.slots.get(i);
			if (slot.container instanceof Inventory) continue;
			ItemStack stack = slot.getItem();
			if (stack.isEmpty()) continue;
			String name = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
			String id = ItemIndex.idOf(stack.getItem());
			if (name.contains("cancel") || name.contains("back")) continue;
			if (name.contains("confirm") || name.contains("buy") || name.contains("purchase") || name.equals("yes")) byName = i;
			else if (id.contains("stained_glass") && (id.contains("lime") || id.contains("green"))) byColour = i;
		}
		return byName >= 0 ? byName : byColour;
	}

	/** A buy confirmation: a chest menu with a confirm item, or a dialog with Yes / Confirm buttons. */
	private static boolean looksLikeConfirm(Screen screen) {
		if (screen instanceof AbstractContainerScreen<?> container) {
			return findConfirmSlot(container.getMenu()) >= 0;
		}
		String title = screen.getTitle().getString().toLowerCase(Locale.ROOT);
		if (title.contains("confirm") || title.contains("buy") || title.contains("purchase") || title.contains("sure")) return true;
		return AutoAuctionController.findConfirmButton(screen) != null && title.contains("auction");
	}

	private static void notifyPlayer(LocalPlayer player, String message) {
		if (Compat.streamerMode()) return;
		player.sendSystemMessage(Component.literal("[AutoDonut] ").withStyle(ChatFormatting.LIGHT_PURPLE)
				.append(Component.literal(message).withStyle(ChatFormatting.YELLOW)));
	}

	private static String seconds(long ms) {
		long s = Math.max(0, (ms + 999) / 1000);
		return s >= 60 ? (s / 60) + "m " + (s % 60) + "s" : s + "s";
	}
}
