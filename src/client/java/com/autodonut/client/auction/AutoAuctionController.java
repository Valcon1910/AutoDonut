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
import com.autodonut.client.Lockdown;
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
			"limit", "maximum", "too many", "cannot", "can't", "not allowed", "invalid", "you must"
	};
	/** Server replies meaning commands are being throttled (lag / rate limit): handled as lag, not a refusal. */
	private static final String[] THROTTLE_WORDS = {
			"too fast", "slow down", "please wait", "cooldown", "try again in", "wait before", "rate limit", "spam"
	};

	/** Server replies meaning every auction slot is in use. */
	private static final String[] SLOT_LIMIT_WORDS = {
			"limit", "maximum", "max ", "too many", "no more", "slots", "full"
	};
	/** How long Auto Auction waits for a slot when they're all taken (sales free one sooner). */
	private static final long SLOTS_FULL_RECHECK_MS = 10 * 60_000L;
	private long slotsFullUntil;

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
	/** When the current step started; a step running longer than {@link #STEP_TIMEOUT_MS} is abandoned. */
	private long phaseStartedAt;
	private long lastTickAt;
	/** Confirmation attempts for the current listing (max {@link #MAX_CONFIRM_ATTEMPTS}, {@link #CONFIRM_RETRY_MS} apart). */
	private int confirmAttempts;
	private String lastCommand = "";
	private static final int MAX_CONFIRM_ATTEMPTS = 3;
	private static final long CONFIRM_RETRY_MS = 3000;

	/** True while a listing is in progress (not idle / counting down). */
	public boolean busy() {
		return phase != Phase.IDLE;
	}
	/** Latest ping to the server (ms) from the player list, and the moment severe lag was last seen. */
	private int ping;
	private long laggingUntil;
	private boolean lagging;

	/** Waits stretch with the ping so listings still complete on a slow connection. */
	private float lagScale() {
		return 1f + Math.min(4f, ping / 400f);
	}

	/** True while a Quick Sell is running. */
	public boolean quickSelling() {
		return quickRule != null;
	}

	public boolean lagging() {
		return lagging;
	}

	/**
	 * Severe lag (ping above 1.2s, or the game freezing for more than a second) pauses all
	 * actions until it has been clear for a few seconds, so nothing is half done when packets
	 * get lost and the server never kicks for a flood of delayed clicks.
	 */
	private void updateLag(LocalPlayer player, long now, long rawGap) {
		var info = player.connection.getPlayerInfo(player.getUUID());
		if (info != null) ping = info.getLatency();
		int fps = Minecraft.getInstance().getFps();
		// Every condition keeps the pause going; it lifts only after 4s of smooth running.
		if (now < warmupUntil) {
			// Joining / loading the world freezes and slows the game briefly; ignore it.
		} else if (ping > 1200) lagFor(now, 4000, "Lagging hard (ping " + ping + "ms)");
		else if (rawGap > 1200) lagFor(now, 4000, "Game froze for " + (rawGap / 100) / 10.0 + "s");
		else if (fps > 0 && fps < 12) lagFor(now, 4000, "Game running slowly (" + fps + " FPS)");
		if (!com.autodonut.client.ServerProbe.responding()) lagFor(now, 1000, "Server not responding");
		lagging = now < laggingUntil;
		if (!lagging) lagReason = "";
	}

	private String lagReason = "";

	private void lagFor(long now, long ms, String reason) {
		laggingUntil = Math.max(laggingUntil, now + ms);
		lagReason = reason;
	}
	private static final long STEP_TIMEOUT_MS = 15_000;

	private void setPhase(Phase next) {
		phase = next;
		phaseStartedAt = System.currentTimeMillis();
	}
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
	/** Nothing is listed right after joining, while the server is still sending menus and messages. */
	private long warmupUntil;
	/** One-off rule for a Quick Sell listing (not saved in the config). */
	private AuctionRule quickRule;
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
		return cfg.autoAuctionEnabled && !Lockdown.active() && (!cfg.onlyOnDonut || ServerContext.isOnDonut());
	}

	/** True while this screen is the auction confirm menu being clicked in the background. */
	public boolean isHidden(Screen screen) {
		if (phase != Phase.CONFIRMING && phase != Phase.AWAIT_CONFIRM && phase != Phase.HOLDING) return false;
		// With Auto Auction off nothing is ever hidden, unless the player started a Quick Sell.
		if (!AutoDonutConfig.get().autoAuctionEnabled && quickRule == null) return false;
		return screen != null && screen == confirmScreen && AutoDonutConfig.get().confirmInBackground;
	}

	/** True while Donut's combat timer is running. */
	public boolean inCombat() {
		return System.currentTimeMillis() < combatUntil;
	}

	/**
	 * Lists one stack right away at the given total price (Quick Sell), using the same
	 * background hand handling and auto-confirm as Auto Auction.
	 */
	public void quickList(int inventorySlot, ItemStack stack, long totalPrice) {
		reset();
		AuctionRule r = new AuctionRule();
		r.items.add(ItemIndex.idOf(stack.getItem()));
		r.priceText = Long.toString(totalPrice);
		r.pricePerItem = false;
		r.mode = com.autodonut.client.config.QuantityMode.EXACTLY;
		r.amount = stack.getCount();
		r.enabled = true;
		quickRule = r;
		rule = r;
		slot = inventorySlot;
		// REACTING moves a main-inventory stack into a free hotbar slot first if needed.
		setPhase(Phase.REACTING);
		phaseUntil = System.currentTimeMillis();
		status = "Quick selling";
	}

	/**
	 * Timers keep running, but nothing is done: when the next action becomes due it waits
	 * ("queued") until the reason is gone. The step watchdog is held back meanwhile.
	 */
	private void queue(String until, long now, long sinceLastTick) {
		phaseStartedAt += sinceLastTick;
		if (phase == Phase.IDLE && now < nextAllowedAt) {
			status = "Next listing in " + seconds(nextAllowedAt - now) + ", then waits until " + until;
		} else if (phase != Phase.IDLE && now < phaseUntil) {
			status = "Working, waits until " + until;
		} else {
			status = "Queued until " + until;
		}
	}

	/** "Continue now" key: force-resets every timer and every paused / disabled item. */
	public void skipWait() {
		long now = System.currentTimeMillis();
		nextAllowedAt = now;
		if (phase == Phase.REACTING || phase == Phase.SPLITTING || phase == Phase.PRE_SEND) phaseUntil = now;
		pausedUntil = 0;
		slotsFullUntil = 0;
		combatUntil = 0;
		warmupUntil = 0;
		laggingUntil = 0;
		lagging = false;
		failures.clear();
		rulePausedUntil.clear();
		status = "Timers reset";
		com.autodonut.client.ui.UiSounds.click();
	}

	public void reset() {
		restoreHand();
		confirmScreen = null;
		quickRule = null;
		splitClicks.clear();
		splitTarget = -1;
		setPhase(Phase.IDLE);
		rule = null;
		slot = -1;
	}

	/** Called when a world is joined. */
	public void onJoin() {
		warmupUntil = System.currentTimeMillis() + 10_000;
		// Loading the world stalls the game; that isn't lag.
		lastTickAt = 0;
		laggingUntil = 0;
		lagging = false;
	}

	public void onDisconnect() {
		reset();
		combatUntil = 0;
		pausedUntil = 0;
		slotsFullUntil = 0;
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
			lastTickAt = 0;
			status = "Not in a world";
			return;
		}
		long rawGap = lastTickAt == 0 ? 0 : now - lastTickAt;
		long sinceLastTick = Math.min(1000, rawGap);
		lastTickAt = now;
		updateLag(player, now, rawGap);
		if (lagging && quickRule == null && phase != Phase.AWAIT_CONFIRM && phase != Phase.CONFIRMING && phase != Phase.HOLDING) {
			queue("the lag clears (ping " + ping + "ms)", now, sinceLastTick);
			// Never leave an item "held" while waiting out lag.
			if (phase == Phase.RESTORE) restoreHand();
			status = "Server not responding (Lag)";
			return;
		}
		boolean panelOpen = mc.gui.screen() instanceof AutoDonutScreen;
		boolean midPrompt = phase == Phase.AWAIT_CONFIRM || phase == Phase.CONFIRMING || phase == Phase.HOLDING;
		if (panelOpen && cfg.pauseInPanel && quickRule == null && !midPrompt) {
			queue("you close AutoDonut", now, sinceLastTick);
			return;
		}
		if (phase != Phase.IDLE && now - phaseStartedAt > STEP_TIMEOUT_MS * lagScale()) {
			// Watchdog: a step that never finished (no prompt, no server answer) is dropped so
			// Auto Auction can never stay stuck; hand and slot are put back by reset().
			reset();
			nextAllowedAt = now + humanizer.between(2000, 5000);
			status = "Retrying shortly";
			return;
		}
		if (Lockdown.active()) {
			reset();
			status = "Offline (safety lockdown)";
			return;
		}
		if (com.autodonut.client.ui.BootOverlay.booting()) {
			status = "Booting up";
			return;
		}
		if (quickRule == null && now < warmupUntil) {
			status = "Starting up";
			return;
		}
		if (quickRule == null && cfg.rules.stream().noneMatch(AuctionRule::isComplete)) {
			reset();
			status = "No items set up";
			return;
		}
		if (!cfg.autoAuctionEnabled && quickRule == null) {
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
			// A prompt being answered is dropped (it becomes visible again and the hand is put
			// back); anything else keeps counting down and waits until combat is over.
			if (phase == Phase.AWAIT_CONFIRM || phase == Phase.CONFIRMING || phase == Phase.HOLDING || confirmScreen != null) reset();
			queue("combat ends (" + seconds(combatUntil - now) + ")", now, sinceLastTick);
			return;
		}
		if (now < pausedUntil) {
			status = "Paused for " + seconds(pausedUntil - now);
			return;
		}
		if (quickRule == null && phase == Phase.IDLE && now < slotsFullUntil) {
			status = "No auction slots";
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
		Screen open = mc.gui.screen();
		boolean otherMenuOpen = open != null && !(open instanceof AutoDonutScreen) && open != confirmScreen
				&& !(open instanceof com.autodonut.client.ui.QuickSellScreen);
		if (cfg.pauseInMenus && otherMenuOpen && quickRule == null && phase != Phase.RESTORE) {
			// Freeze: every timer stands still while a chest or menu is open and resumes where it
			// left off once it closes, so playing normally never races a listing.
			nextAllowedAt += sinceLastTick;
			phaseUntil += sinceLastTick;
			phaseStartedAt += sinceLastTick;
			status = "Frozen while a menu is open";
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
		int scanEnd = cfg.inventoryItems == 1 ? 9 : 36;
		// First choice: a stack that already matches a rule.
		for (int i = 0; i < scanEnd; i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.isEmpty()) continue;
			AuctionRule match = findRule(cfg, stack, now);
			if (match != null) {
				rule = match;
				slot = i;
				setPhase(Phase.REACTING);
				phaseUntil = now + Math.round(humanizer.reaction(cfg.maxReactionSeconds) * cfg.speedFactor());
				status = "Found " + stack.getHoverName().getString();
				return;
			}
		}
		// Otherwise: a stack that is too big; split the wanted amount off it.
		int empty = emptySlot(inv, cfg);
		if (empty >= 0 && player.containerMenu == player.inventoryMenu) {
			for (int i = 0; i < scanEnd; i++) {
				ItemStack stack = inv.getItem(i);
				if (stack.isEmpty()) continue;
				for (AuctionRule r : cfg.rules) {
					Long until = rulePausedUntil.get(r);
					if (until != null && now < until) continue;
					int want = r.splitAmount(ItemIndex.idOf(stack.getItem()), ItemIndex.tagsOf(stack), stack.getCount());
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
			if (findRule(cfg, stack, now) != null) return true;
			for (AuctionRule r : cfg.rules) {
				if (r.splitAmount(id, ItemIndex.tagsOf(stack), stack.getCount()) > 0) return true;
			}
		}
		return false;
	}

	/** True when the total count of items matching any rule went up since the last check. */
	private boolean pickedUpNewItem(Inventory inv, AutoDonutConfig cfg) {
		java.util.Map<String, Integer> counts = new java.util.HashMap<>();
		for (int r = 0; r < cfg.rules.size(); r++) {
			AuctionRule rule = cfg.rules.get(r);
			if (!rule.enabled || !rule.hasItems()) continue;
			int total = 0;
			for (int i = 0; i < 36; i++) {
				ItemStack stack = inv.getItem(i);
				if (!stack.isEmpty() && rule.itemMatches(ItemIndex.idOf(stack.getItem()), ItemIndex.tagsOf(stack))) total += stack.getCount();
			}
			counts.put(Integer.toString(r), total);
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
	private static int emptySlot(Inventory inv, AutoDonutConfig cfg) {
		int hotbar = freeHotbarSlot(inv);
		if (hotbar >= 0 || cfg.inventoryItems == 1) return hotbar;
		for (int i = 9; i < 36; i++) {
			if (inv.getItem(i).isEmpty()) return i;
		}
		return -1;
	}

	/** An empty hotbar slot that isn't the selected one (the selected slot is never touched), or -1. */
	private static int freeHotbarSlot(Inventory inv) {
		int sel = inv.getSelectedSlot();
		for (int i = 0; i < 9; i++) {
			if (i != sel && inv.getItem(i).isEmpty()) return i;
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
		setPhase(Phase.SPLITTING);
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
		setPhase(Phase.REACTING);
		phaseUntil = now + Math.round(humanizer.handling() * AutoDonutConfig.get().speedFactor());
	}

	private void tickReacting(Minecraft mc, LocalPlayer player, long now) {
		if (now < phaseUntil) return;
		Inventory inv = player.getInventory();
		if (!stillMatches(inv.getItem(slot))) {
			reset();
			return;
		}
		if (!Inventory.isHotbarSlot(slot)) {
			// Never equip it: move it into a free hotbar slot (not the selected one) and list from there.
			int target = freeHotbarSlot(inv);
			if (target < 0 || player.containerMenu != player.inventoryMenu
					|| !InventoryActions.swap(mc, menuSlot(slot), target)) {
				status = "No free hotbar slot";
				nextAllowedAt = now + 5000;
				reset();
				return;
			}
			slot = target;
			phaseUntil = now + Math.round(humanizer.handling() * AutoDonutConfig.get().speedFactor());
			return;
		}
		setPhase(Phase.PRE_SEND);
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
		if (handMode != 0) return true; // already held
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
		// Items outside the hotbar are moved to the hotbar first (see tickReacting), never into the hand.
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
		lastCommand = command;
		confirmAttempts = 1;
		player.connection.sendCommand(command);
		recentListings.addLast(now);
		listedThisSession++;

		serverConfirmed = false;
		if (cfg.autoConfirm) {
			// Keep the item held: Donut runs commands a moment after receiving them, so putting it
			// back now would make it see an empty hand. It's restored once Donut has answered.
			setPhase(Phase.AWAIT_CONFIRM);
			phaseUntil = now + Math.round(CONFIRM_RETRY_MS * lagScale());
			status = "Waiting for confirmation";
		} else {
			setPhase(Phase.RESTORE);
			phaseUntil = now + humanizer.between(700, 1600);
			status = "Listed, waiting";
		}
	}

	private void tickAwaitConfirm(Minecraft mc, long now) {
		Screen screen = mc.gui.screen();
		if (screen != null && !(screen instanceof AutoDonutScreen) && !(screen instanceof ChatScreen) && looksLikeSellPrompt(screen)) {
			confirmScreen = screen;
			setPhase(Phase.CONFIRMING);
			// A visible prompt gets a human-like pause. A hidden one is answered right away: while
			// any screen is open the game ignores movement and keys, so it must close quickly.
			phaseUntil = AutoDonutConfig.get().confirmInBackground && AutoDonutConfig.get().instantBackgroundConfirm
					? now + humanizer.between(40, 110)
					: now + Math.round(humanizer.between(300, 850) * AutoDonutConfig.get().speedFactor());
			status = "Confirming listing";
		} else if (now > phaseUntil) {
			LocalPlayer player = mc.player;
			boolean itemStillThere = player != null && !serverConfirmed && stillMatches(sourceStack(player));
			if (itemStillThere && confirmAttempts < MAX_CONFIRM_ATTEMPTS) {
				// No prompt yet and nothing listed: try again (at most 3 times, 3s apart).
				confirmAttempts++;
				lastCommandAt = now;
				player.connection.sendCommand(lastCommand);
				phaseUntil = now + Math.round(CONFIRM_RETRY_MS * lagScale());
				status = "Retrying confirmation (" + confirmAttempts + "/" + MAX_CONFIRM_ATTEMPTS + ")";
			} else {
				// Listed directly without a prompt, or out of attempts. Out of attempts with no
				// answer at all means commands aren't getting through: pause like lag.
				if (itemStillThere) lagFor(now, 10_000, "Server isn't answering commands (lag)");
				setPhase(Phase.RESTORE);
				phaseUntil = now + humanizer.between(300, 900);
			}
		}
	}

	/**
	 * Only screens that are about selling count as the confirm prompt; anything else that
	 * happens to open (resource pack prompts, server welcome dialogs, ...) is left alone.
	 */
	private static boolean looksLikeSellPrompt(Screen screen) {
		String title = screen.getTitle().getString().toLowerCase(Locale.ROOT);
		for (String w : new String[]{"sell", "sure", "confirm", "auction", "listing", "price"}) {
			if (title.contains(w)) return true;
		}
		if (screen instanceof AbstractContainerScreen<?> container) {
			return findConfirmSlot(container.getMenu()) >= 0;
		}
		List<Button> buttons = new ArrayList<>();
		collectButtons(screen.children(), buttons);
		boolean yes = false;
		boolean no = false;
		for (Button b : buttons) {
			String t = b.getMessage().getString().trim().toLowerCase(Locale.ROOT);
			if (t.equals("yes") || t.contains("confirm")) yes = true;
			if (t.equals("no") || t.contains("cancel")) no = true;
		}
		return yes && no;
	}

	private void tickConfirming(Minecraft mc, LocalPlayer player, long now) {
		if (now < phaseUntil) return;
		Screen open = mc.gui.screen();
		if (open == null || open != confirmScreen) {
			// The menu closed by itself.
			confirmScreen = null;
			setPhase(Phase.RESTORE);
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
			setPhase(Phase.HOLDING);
			holdUntil = now + Math.round(CONFIRM_RETRY_MS * lagScale());
		} else {
			setPhase(Phase.RESTORE);
			phaseUntil = now + humanizer.between(700, 1400);
		}
	}

	private void tickHolding(long now) {
		if (!serverConfirmed && now < holdUntil) return;
		Screen open = Minecraft.getInstance().gui.screen();
		if (!serverConfirmed && confirmAttempts < MAX_CONFIRM_ATTEMPTS && open != null && open == confirmScreen) {
			// The prompt is still there 3s after clicking: click it again.
			confirmAttempts++;
			setPhase(Phase.CONFIRMING);
			phaseUntil = now + humanizer.between(40, 110);
			status = "Retrying confirmation (" + confirmAttempts + "/" + MAX_CONFIRM_ATTEMPTS + ")";
			return;
		}
		restoreHand();
		setPhase(Phase.RESTORE);
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
		if (!serverConfirmed && now - lastCommandAt < 4000 * lagScale()) return;
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
			nextAllowedAt = now + Math.round(humanizer.between(600, 1800) * cfg.speedFactor());
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
		if (slotsFullUntil != 0 && (lower.contains("sold") || lower.contains("expired") || lower.contains("bought"))) {
			slotsFullUntil = 0;
		}
		if (lastCommandAt == 0 || now - lastCommandAt > 6000) return;
		for (String w : THROTTLE_WORDS) {
			if (lower.contains(w)) {
				lagFor(now, 10_000, "Commands are being blocked (server lag)");
				lagging = true;
				return;
			}
		}
		if (lower.contains("you listed") || lower.contains("listed for") || lower.contains("put up for auction")) {
			serverConfirmed = true;
			if (rule != null) failures.remove(rule);
			return;
		}
		LocalPlayer player = Minecraft.getInstance().player;
		boolean quick = quickRule != null;
		if (isSlotLimit(lower)) {
			lastCommandAt = 0;
			if (rule != null) failures.remove(rule);
			if (quick) {
				// Quick Sell only reports it; Auto Auction keeps running.
				if (player != null) notifyPlayer(player, "Can't auction: there are no free auction slots.");
			} else {
				slotsFullUntil = now + SLOTS_FULL_RECHECK_MS;
				if (player != null) notifyPlayer(player, "All auction slots are in use, Auto Auction pauses until one frees up.");
			}
			reset();
			return;
		}
		for (String word : REFUSAL_WORDS) {
			if (lower.contains(word)) {
				lastCommandAt = 0;
				if (quick) {
					if (player != null) notifyPlayer(player, "The server refused the Quick Sell.");
					reset();
					return;
				}
				pausedUntil = now + SERVER_REFUSAL_PAUSE_MS;
				if (player != null) notifyPlayer(player, "The server refused a listing, pausing Auto Auction for 5 minutes.");
				return;
			}
		}
	}

	private static boolean isSlotLimit(String lower) {
		boolean aboutListings = lower.contains("listing") || lower.contains("auction") || lower.contains("slot") || lower.contains("items");
		if (!aboutListings) return false;
		for (String w : SLOT_LIMIT_WORDS) {
			if (lower.contains(w)) return true;
		}
		return false;
	}

	private AuctionRule findRule(AutoDonutConfig cfg, ItemStack stack, long now) {
		String itemId = ItemIndex.idOf(stack.getItem());
		java.util.Set<String> tags = ItemIndex.tagsOf(stack);
		int count = stack.getCount();
		for (AuctionRule r : cfg.rules) {
			Long until = rulePausedUntil.get(r);
			if (until != null && now < until) continue;
			if (r.matches(itemId, tags, count)) return r;
		}
		return null;
	}

	private boolean stillMatches(ItemStack stack) {
		return rule != null && !stack.isEmpty() && (rule == quickRule || AutoDonutConfig.get().rules.contains(rule))
				&& rule.matches(ItemIndex.idOf(stack.getItem()), ItemIndex.tagsOf(stack), stack.getCount());
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
