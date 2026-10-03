package com.autodonut.client.auction;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import com.autodonut.client.Compat;
import com.autodonut.client.ServerContext;
import com.autodonut.client.config.AuctionRule;
import com.autodonut.client.config.AutoDonutConfig;
import com.autodonut.client.config.PriceFormat;
import com.autodonut.client.ui.AutoDonutScreen;

/**
 * Watches the inventory and lists matching items on the auction house, one step per
 * randomised delay: notice item -> select it -> type command -> restore slot -> wait.
 */
public final class AutoAuctionController {
	private static final AutoAuctionController INSTANCE = new AutoAuctionController();
	private static final long HOUR_MS = 60L * 60L * 1000L;
	private static final long FAILURE_PAUSE_MS = 10L * 60L * 1000L;
	private static final long SERVER_REFUSAL_PAUSE_MS = 5L * 60L * 1000L;
	private static final String[] REFUSAL_WORDS = {
			"limit", "maximum", "too many", "cannot", "can't", "not allowed", "cooldown", "invalid", "you must"
	};

	private enum Phase { IDLE, SPLITTING, REACTING, PRE_SEND, AWAIT_CONFIRM, CONFIRMING, HOLDING, RESTORE }

	/** How long to wait for a confirmation menu after sending the sell command. */
	private static final long CONFIRM_WAIT_MS = 4000;
	/** Donut SMP's combat tag lasts about 15s; stay paused a little longer after the last sign of combat. */
	private static final long COMBAT_PAUSE_MS = 17_000;

	private final Humanizer humanizer = new Humanizer(new Random());
	private final Deque<Long> recentListings = new ArrayDeque<>();
	private final Map<AuctionRule, Integer> failures = new IdentityHashMap<>();
	private final Map<AuctionRule, Long> rulePausedUntil = new IdentityHashMap<>();

	private Phase phase = Phase.IDLE;
	private long phaseUntil;
	private long nextAllowedAt;
	private long pausedUntil;
	private long lastCommandAt;
	private AuctionRule rule;
	private int slot = -1;
	/** How the item is currently "held" for the server: 0 = not, 1 = server-side slot switch, 2 = swapped. */
	private int handMode;
	/** Selected hotbar slot when the item was put in hand. */
	private int heldSlot;
	/** Latest time to keep the item held after confirming while waiting for the server. */
	private long holdUntil;
	private String listedItemId = "";
	private int listedCount;
	private int listedThisSession;
	private String status = "Disabled";
	/** Confirmation menu currently being handled; hidden from view when confirming in the background. */
	private Screen confirmScreen;
	private long combatUntil;
	/** Set when the server reports the listing ("You listed ..."). */
	private boolean serverConfirmed;
	/** Total count per rule item last tick, to notice newly picked-up items. */
	private final java.util.Map<String, Integer> lastCounts = new java.util.HashMap<>();
	/** Pending inventory clicks for splitting a stack: {menuSlot, button}. */
	private final ArrayDeque<int[]> splitClicks = new ArrayDeque<>();
	private int splitTarget = -1;
	private int splitAmount;

	public static AutoAuctionController get() {
		return INSTANCE;
	}

	public String status() {
		return status;
	}

	public int listedThisSession() {
		return listedThisSession;
	}

	/** Listings made in the last rolling hour. */
	public int listedLastHour() {
		long now = System.currentTimeMillis();
		int n = 0;
		for (long t : recentListings) {
			if (now - t <= HOUR_MS) n++;
		}
		return n;
	}

	public boolean isActive() {
		AutoDonutConfig cfg = AutoDonutConfig.get();
		return cfg.autoAuctionEnabled && (!cfg.onlyOnDonut || ServerContext.isOnDonut());
	}

	/** True while this screen is the auction confirm menu being clicked in the background. */
	public boolean isHidden(Screen screen) {
		return screen != null && screen == confirmScreen && AutoDonutConfig.get().confirmInBackground;
	}

	public void reset() {
		restoreHand();
		confirmScreen = null;
		splitClicks.clear();
		splitTarget = -1;
		phase = Phase.IDLE;
		rule = null;
		slot = -1;
	}

	public void onDisconnect() {
		reset();
		combatUntil = 0;
		pausedUntil = 0;
		nextAllowedAt = 0;
		failures.clear();
		rulePausedUntil.clear();
	}

	public void tick(Minecraft mc) {
		long now = System.currentTimeMillis();
		AutoDonutConfig cfg = AutoDonutConfig.get();
		LocalPlayer player = mc.player;

		if (player == null || mc.gameMode == null) {
			reset();
			status = "Not in a world";
			return;
		}
		if (!cfg.autoAuctionEnabled) {
			reset();
			status = "Disabled";
			return;
		}
		if (cfg.onlyOnDonut && !ServerContext.isOnDonut()) {
			reset();
			status = "Waiting for Donut SMP";
			return;
		}
		if (now < combatUntil) {
			// Drop whatever was in progress (an open confirm menu becomes visible again);
			// nothing is touched while in combat.
			if (phase != Phase.IDLE || confirmScreen != null) reset();
			status = "Paused, in combat (" + seconds(combatUntil - now) + ")";
			return;
		}
		if (now < pausedUntil) {
			status = "Paused for " + seconds(pausedUntil - now);
			return;
		}
		if (phase == Phase.AWAIT_CONFIRM) {
			tickAwaitConfirm(mc, now);
			return;
		}
		if (phase == Phase.CONFIRMING) {
			tickConfirming(mc, player, now);
			return;
		}
		if (phase == Phase.HOLDING) {
			tickHolding(now);
			return;
		}
		boolean otherMenuOpen = mc.gui.screen() != null && !(mc.gui.screen() instanceof AutoDonutScreen);
		if (cfg.pauseInMenus && otherMenuOpen) {
			// Push pending steps back so nothing fires the instant the menu closes.
			phaseUntil = Math.max(phaseUntil, now + humanizer.between(400, 1200));
			status = "Paused while a menu is open";
			return;
		}
		while (!recentListings.isEmpty() && now - recentListings.peekFirst() > HOUR_MS) {
			recentListings.pollFirst();
		}

		switch (phase) {
			case IDLE -> tickIdle(player, cfg, now);
			case SPLITTING -> tickSplitting(mc, player, now);
			case REACTING -> tickReacting(mc, player, now);
			case PRE_SEND -> tickPreSend(player, cfg, now);
			case RESTORE -> tickRestore(mc, player, cfg, now);
			default -> { }
		}
	}

	private void tickIdle(LocalPlayer player, AutoDonutConfig cfg, long now) {
		if (recentListings.size() >= cfg.maxListingsPerHour) {
			long wait = HOUR_MS - (now - recentListings.peekFirst());
			status = "Hourly limit reached, resumes in " + seconds(wait);
			return;
		}
		Inventory inv = player.getInventory();
		if (pickedUpNewItem(inv, cfg)) {
			// A new matching item arrived: check it after a short reaction instead of waiting out the full delay.
			long soon = Math.max(lastCommandAt + 1500, now + Math.round(humanizer.reaction(cfg.maxReactionSeconds) * cfg.speedFactor()));
			nextAllowedAt = Math.min(nextAllowedAt, soon);
		}
		if (now < nextAllowedAt) {
			status = "Next listing in " + seconds(nextAllowedAt - now);
			return;
		}
		// First choice: a stack that already matches a rule.
		for (int i = 0; i < 36; i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.isEmpty()) continue;
			AuctionRule match = findRule(cfg, ItemIndex.idOf(stack.getItem()), stack.getCount(), now);
			if (match != null) {
				rule = match;
				slot = i;
				phase = Phase.REACTING;
				phaseUntil = now + Math.round(humanizer.reaction(cfg.maxReactionSeconds) * cfg.speedFactor());
				status = "Found " + stack.getHoverName().getString();
				return;
			}
		}
		// Otherwise: a stack that is too big; split the wanted amount off it.
		int empty = emptySlot(inv);
		if (empty >= 0 && player.containerMenu == player.inventoryMenu) {
			for (int i = 0; i < 36; i++) {
				ItemStack stack = inv.getItem(i);
				if (stack.isEmpty()) continue;
				for (AuctionRule r : cfg.rules) {
					Long until = rulePausedUntil.get(r);
					if (until != null && now < until) continue;
					int want = r.splitAmount(ItemIndex.idOf(stack.getItem()), stack.getCount());
					if (want > 0) {
						startSplit(r, i, empty, want, stack.getCount(), now);
						status = "Splitting " + want + " " + stack.getHoverName().getString();
						return;
					}
				}
			}
		}
		status = "Watching inventory";
	}

	/** Whether any stack still matches a rule, or could be split to match one. */
	private boolean hasMoreToList(Inventory inv, AutoDonutConfig cfg, long now) {
		for (int i = 0; i < 36; i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.isEmpty()) continue;
			String id = ItemIndex.idOf(stack.getItem());
			if (findRule(cfg, id, stack.getCount(), now) != null) return true;
			for (AuctionRule r : cfg.rules) {
				if (r.splitAmount(id, stack.getCount()) > 0) return true;
			}
		}
		return false;
	}

	/** True when the total of any rule item went up since the last check. */
	private boolean pickedUpNewItem(Inventory inv, AutoDonutConfig cfg) {
		java.util.Map<String, Integer> counts = new java.util.HashMap<>();
		for (AuctionRule r : cfg.rules) {
			if (r.enabled && !r.itemId.isEmpty()) counts.put(r.itemId, 0);
		}
		for (int i = 0; i < 36; i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.isEmpty()) continue;
			String id = ItemIndex.idOf(stack.getItem());
			counts.computeIfPresent(id, (k, v) -> v + stack.getCount());
		}
		boolean increased = false;
		for (var e : counts.entrySet()) {
			Integer before = lastCounts.get(e.getKey());
			if (before != null && e.getValue() > before) increased = true;
		}
		lastCounts.clear();
		lastCounts.putAll(counts);
		return increased;
	}

	/** An empty main-inventory slot (the hotbar is left alone), or -1. */
	private static int emptySlot(Inventory inv) {
		for (int i = 9; i < 36; i++) {
			if (inv.getItem(i).isEmpty()) return i;
		}
		return -1;
	}

	/** Inventory index (0-35) to the slot number in the player's inventory menu. */
	private static int menuSlot(int inventoryIndex) {
		return inventoryIndex < 9 ? 36 + inventoryIndex : inventoryIndex;
	}

	/**
	 * Splits {@code amount} items off a stack the same way a player would: pick the stack up,
	 * right-click one item at a time into the empty slot, then put the rest back.
	 */
	private void startSplit(AuctionRule r, int from, int to, int amount, int count, long now) {
		rule = r;
		splitTarget = to;
		splitAmount = amount;
		splitClicks.clear();
		int src = menuSlot(from);
		int dst = menuSlot(to);
		if (amount * 2 == count) {
			// Exactly half: right-click picks up half in one go.
			splitClicks.add(new int[]{src, 1});
			splitClicks.add(new int[]{dst, 0});
		} else {
			splitClicks.add(new int[]{src, 0});
			for (int i = 0; i < amount; i++) splitClicks.add(new int[]{dst, 1});
			splitClicks.add(new int[]{src, 0});
		}
		phase = Phase.SPLITTING;
		phaseUntil = now + Math.round(humanizer.reaction(AutoDonutConfig.get().maxReactionSeconds) * AutoDonutConfig.get().speedFactor());
	}

	private void tickSplitting(Minecraft mc, LocalPlayer player, long now) {
		if (now < phaseUntil) return;
		if (player.containerMenu != player.inventoryMenu) {
			reset();
			return;
		}
		// A few clicks per tick, like fast right-clicking.
		int burst = 1 + (int) (Math.random() * 3);
		for (int i = 0; i < burst && !splitClicks.isEmpty(); i++) {
			int[] click = splitClicks.poll();
			if (!InventoryActions.click(mc, player.inventoryMenu.containerId, click[0], click[1])) {
				fail("Couldn't split the stack");
				return;
			}
		}
		if (!splitClicks.isEmpty()) {
			phaseUntil = now + Math.round(humanizer.between(40, 110) * AutoDonutConfig.get().speedFactor());
			return;
		}
		ItemStack made = player.getInventory().getItem(splitTarget);
		if (made.getCount() != splitAmount || !stillMatches(made)) {
			reset();
			return;
		}
		slot = splitTarget;
		splitTarget = -1;
		phase = Phase.REACTING;
		phaseUntil = now + Math.round(humanizer.handling() * AutoDonutConfig.get().speedFactor());
	}

	private void tickReacting(Minecraft mc, LocalPlayer player, long now) {
		if (now < phaseUntil) return;
		if (!stillMatches(player.getInventory().getItem(slot))) {
			reset();
			return;
		}
		phase = Phase.PRE_SEND;
		phaseUntil = now + Math.round(humanizer.handling() * AutoDonutConfig.get().speedFactor());
		status = "Preparing listing";
	}

	/**
	 * Makes the server see {@link #slot}'s item as the held item, without the player seeing
	 * anything: a hotbar item is "selected" only on the server (the client's selection never
	 * changes); a main-inventory item is swapped into the held slot, which {@link #restoreHand}
	 * undoes in the same tick or right after the server answers.
	 */
	private boolean putInHand(LocalPlayer player) {
		Inventory inv = player.getInventory();
		heldSlot = inv.getSelectedSlot();
		if (slot == heldSlot) {
			handMode = 0;
			return true;
		}
		if (Inventory.isHotbarSlot(slot)) {
			player.connection.send(new ServerboundSetCarriedItemPacket(slot));
			handMode = 1;
			return true;
		}
		if (InventoryActions.swap(Minecraft.getInstance(), menuSlot(slot), heldSlot)) {
			handMode = 2;
			return true;
		}
		return false;
	}

	private void restoreHand() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player != null) {
			if (handMode == 1) {
				// Tell the server about the slot the player actually has selected (they may have scrolled).
				player.connection.send(new ServerboundSetCarriedItemPacket(player.getInventory().getSelectedSlot()));
			} else if (handMode == 2) {
				InventoryActions.swap(mc, menuSlot(slot), heldSlot);
			}
		}
		handMode = 0;
	}

	/** The stack the server will treat as held while {@link #putInHand} is in effect. */
	private ItemStack sourceStack(LocalPlayer player) {
		return player.getInventory().getItem(slot);
	}

	private void tickPreSend(LocalPlayer player, AutoDonutConfig cfg, long now) {
		if (now < phaseUntil) return;
		ItemStack held = sourceStack(player);
		if (held.isEmpty() || !stillMatches(held)) {
			reset();
			return;
		}
		held = held.copy();
		if (!putInHand(player)) {
			fail("Couldn't prepare the item for listing");
			return;
		}
		long total = rule.totalPrice(held.getCount());
		String command = cfg.sellCommand.replace("{price}", Long.toString(total)).trim();
		if (command.startsWith("/")) command = command.substring(1);

		listedItemId = ItemIndex.idOf(held.getItem());
		listedCount = held.getCount();
		lastCommandAt = now;
		player.connection.sendCommand(command);
		recentListings.addLast(now);
		listedThisSession++;

		serverConfirmed = false;
		if (cfg.autoConfirm) {
			// Put things back in the same tick; the item is held again only for the confirm click.
			restoreHand();
			phase = Phase.AWAIT_CONFIRM;
			phaseUntil = now + CONFIRM_WAIT_MS;
			status = "Waiting for confirmation";
		} else {
			phase = Phase.RESTORE;
			phaseUntil = now + humanizer.between(700, 1600);
			status = "Listed, waiting";
		}
	}

	private void tickAwaitConfirm(Minecraft mc, long now) {
		Screen screen = mc.gui.screen();
		if (screen != null && !(screen instanceof AutoDonutScreen) && !(screen instanceof ChatScreen)) {
			confirmScreen = screen;
			phase = Phase.CONFIRMING;
			// A human needs a moment to find the button.
			phaseUntil = now + Math.round(humanizer.between(300, 850) * AutoDonutConfig.get().speedFactor());
			status = "Confirming listing";
		} else if (now > phaseUntil) {
			// No confirmation menu appeared; the listing went through directly.
			phase = Phase.RESTORE;
			phaseUntil = now + humanizer.between(300, 900);
		}
	}

	private void tickConfirming(Minecraft mc, LocalPlayer player, long now) {
		if (now < phaseUntil) return;
		Screen open = mc.gui.screen();
		if (open == null || open != confirmScreen) {
			// The menu closed by itself.
			confirmScreen = null;
			phase = Phase.RESTORE;
			phaseUntil = now + humanizer.between(300, 900);
			return;
		}
		boolean confirmed = false;
		if (open instanceof AbstractContainerScreen<?> container) {
			// Chest-style confirm menu: click the confirm item.
			AbstractContainerMenu menu = container.getMenu();
			int slot = findConfirmSlot(menu);
			confirmed = slot >= 0 && InventoryActions.leftClick(mc, menu.containerId, slot);
		} else {
			// Dialog-style prompt ("Are you sure you want to sell this?" with No / Yes).
			Button button = findConfirmButton(open);
			if (button != null && stillMatches(sourceStack(player)) && putInHand(player)) {
				button.onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
				confirmed = true;
			}
		}
		if (confirmed) {
			status = "Confirmed";
		} else {
			// Show the menu so the player can confirm by hand.
			confirmScreen = null;
			notifyPlayer(player, "Couldn't find the confirm button, please confirm the listing yourself.");
		}
		if (confirmed && handMode != 0) {
			// Keep it held (server-side) until Donut reports the listing, then put everything back.
			phase = Phase.HOLDING;
			holdUntil = now + 1500;
		} else {
			phase = Phase.RESTORE;
			phaseUntil = now + humanizer.between(700, 1400);
		}
	}

	private void tickHolding(long now) {
		if (!serverConfirmed && now < holdUntil) return;
		restoreHand();
		phase = Phase.RESTORE;
		phaseUntil = now + humanizer.between(300, 700);
	}

	/**
	 * The confirm button in the auction menu: the last slot whose name mentions "confirm",
	 * otherwise the last lime/green item (the usual confirm colour). Player inventory slots
	 * are ignored.
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
			if (name.contains("confirm")) byName = i;
			else if (id.contains("lime") || id.contains("green")) byColour = i;
		}
		return byName >= 0 ? byName : byColour;
	}

	/**
	 * The confirming button of a dialog: the last enabled button labelled Yes / Confirm /
	 * Sell / Accept, otherwise the last enabled button that isn't No / Cancel / Back.
	 */
	private static Button findConfirmButton(Screen screen) {
		List<Button> buttons = new ArrayList<>();
		collectButtons(screen.children(), buttons);
		Button labelled = null;
		Button fallback = null;
		for (Button b : buttons) {
			if (!b.active || !b.visible) continue;
			String t = b.getMessage().getString().trim().toLowerCase(Locale.ROOT);
			if (t.equals("no") || t.contains("cancel") || t.contains("back") || t.contains("close")) continue;
			fallback = b;
			if (t.contains("yes") || t.contains("confirm") || t.contains("sell") || t.contains("accept")) labelled = b;
		}
		return labelled != null ? labelled : fallback;
	}

	private static void collectButtons(List<? extends GuiEventListener> children, List<Button> out) {
		for (GuiEventListener child : children) {
			if (child instanceof Button b) {
				if (!out.contains(b)) out.add(b);
				continue;
			}
			if (child instanceof ContainerEventHandler container) collectButtons(container.children(), out);
			if (child instanceof LayoutElement layout) {
				layout.visitWidgets(w -> {
					if (w instanceof Button b && !out.contains(b)) out.add(b);
				});
			}
		}
	}

	private void tickRestore(Minecraft mc, LocalPlayer player, AutoDonutConfig cfg, long now) {
		if (now < phaseUntil) return;
		// Give the server a few seconds to report the listing before judging it.
		if (!serverConfirmed && now - lastCommandAt < 4000) return;
		// Close a confirmation menu the server left open.
		if (confirmScreen != null && mc.gui.screen() == confirmScreen) {
			if (confirmScreen instanceof AbstractContainerScreen<?>) player.closeContainer();
			else confirmScreen.onClose();
		}
		confirmScreen = null;
		restoreHand();
		Inventory inv = player.getInventory();
		ItemStack held = inv.getItem(slot);

		// If the exact stack is still in hand, the server most likely refused the listing.
		boolean unchanged = !serverConfirmed && !held.isEmpty()
				&& ItemIndex.idOf(held.getItem()).equals(listedItemId) && held.getCount() == listedCount;
		if (unchanged) {
			int count = failures.merge(rule, 1, Integer::sum);
			if (count >= 2) {
				rulePausedUntil.put(rule, now + FAILURE_PAUSE_MS);
				failures.remove(rule);
				notifyPlayer(player, "Listing " + held.getHoverName().getString()
						+ " keeps failing, pausing that item for 10 minutes. Check the price and /ah.");
			}
		} else {
			failures.remove(rule);
		}

		if (!unchanged && hasMoreToList(inv, cfg, now)) {
			// More of the same job waiting: keep going at a quick, still irregular pace (no breaks mid-batch).
			nextAllowedAt = now + Math.round(humanizer.between(1200, 3500) * cfg.speedFactor());
		} else {
			nextAllowedAt = now + Math.round(humanizer.nextListingDelay(cfg.minDelaySeconds, cfg.maxDelaySeconds, cfg.randomBreaks)
					* cfg.listingDelayFactor());
		}
		reset();
	}

	/** Called for every system (non-player) message, including the action bar ({@code overlay}). */
	public void onGameMessage(Component message, boolean overlay) {
		long now = System.currentTimeMillis();
		String lower = message.getString().toLowerCase(Locale.ROOT);
		// Donut SMP shows a combat timer in the action bar and announces combat in chat.
		if (lower.contains("combat")) {
			if (lower.contains("no longer") || lower.contains("out of combat") || lower.contains("left combat")) {
				combatUntil = 0;
			} else {
				combatUntil = now + COMBAT_PAUSE_MS;
			}
			return;
		}
		if (overlay) return;
		if (lastCommandAt == 0 || now - lastCommandAt > 6000) return;
		if (lower.contains("you listed") || lower.contains("listed for") || lower.contains("put up for auction")) {
			serverConfirmed = true;
			if (rule != null) failures.remove(rule);
			return;
		}
		String text = message.getString().toLowerCase(Locale.ROOT);
		for (String word : REFUSAL_WORDS) {
			if (text.contains(word)) {
				pausedUntil = now + SERVER_REFUSAL_PAUSE_MS;
				lastCommandAt = 0;
				LocalPlayer player = Minecraft.getInstance().player;
				if (player != null) notifyPlayer(player, "The server refused a listing, pausing Auto Auction for 5 minutes.");
				return;
			}
		}
	}

	private AuctionRule findRule(AutoDonutConfig cfg, String itemId, int count, long now) {
		for (AuctionRule r : cfg.rules) {
			Long until = rulePausedUntil.get(r);
			if (until != null && now < until) continue;
			if (r.matches(itemId, count)) return r;
		}
		return null;
	}

	private boolean stillMatches(ItemStack stack) {
		return rule != null && !stack.isEmpty() && AutoDonutConfig.get().rules.contains(rule)
				&& rule.matches(ItemIndex.idOf(stack.getItem()), stack.getCount());
	}

	private static int emptyHotbarSlot(Inventory inv) {
		for (int i = 0; i < 9; i++) {
			if (inv.getItem(i).isEmpty()) return i;
		}
		return -1;
	}

	private void fail(String reason) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player != null) notifyPlayer(player, reason);
		pausedUntil = System.currentTimeMillis() + 30_000;
		reset();
	}

	private static void notifyPlayer(LocalPlayer player, String message) {
		if (Compat.streamerMode()) return;
		player.sendSystemMessage(prefix().append(Component.literal(message).withStyle(ChatFormatting.YELLOW)));
	}

	private static net.minecraft.network.chat.MutableComponent prefix() {
		return Component.literal("[AutoDonut] ").withStyle(ChatFormatting.LIGHT_PURPLE);
	}

	private static String seconds(long ms) {
		long s = Math.max(0, (ms + 999) / 1000);
		return s >= 60 ? (s / 60) + "m " + (s % 60) + "s" : s + "s";
	}
}
