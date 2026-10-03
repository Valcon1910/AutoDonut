package com.autodonut.client.auction;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
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

	private enum Phase { IDLE, REACTING, PRE_SEND, AWAIT_CONFIRM, CONFIRMING, RESTORE }

	/** How long to wait for a confirmation menu after sending the sell command. */
	private static final long CONFIRM_WAIT_MS = 4000;

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
	private int previousSelected = -1;
	private String listedItemId = "";
	private int listedCount;
	private int listedThisSession;
	private String status = "Disabled";
	/** Confirmation menu currently being handled; hidden from view when confirming in the background. */
	private Screen confirmScreen;

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
		confirmScreen = null;
		phase = Phase.IDLE;
		rule = null;
		slot = -1;
		previousSelected = -1;
	}

	public void onDisconnect() {
		reset();
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
		if (now < nextAllowedAt) {
			status = "Next listing in " + seconds(nextAllowedAt - now);
			return;
		}
		Inventory inv = player.getInventory();
		for (int i = 0; i < 36; i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.isEmpty()) continue;
			AuctionRule match = findRule(cfg, ItemIndex.idOf(stack.getItem()), stack.getCount(), now);
			if (match != null) {
				rule = match;
				slot = i;
				phase = Phase.REACTING;
				phaseUntil = now + humanizer.reaction(cfg.maxReactionSeconds);
				status = "Found " + stack.getHoverName().getString();
				return;
			}
		}
		status = "Watching inventory";
	}

	private void tickReacting(Minecraft mc, LocalPlayer player, long now) {
		if (now < phaseUntil) return;
		Inventory inv = player.getInventory();
		if (!stillMatches(inv.getItem(slot))) {
			reset();
			return;
		}
		previousSelected = inv.getSelectedSlot();

		if (!Inventory.isHotbarSlot(slot)) {
			int target = emptyHotbarSlot(inv);
			if (target < 0) target = previousSelected;
			if (!InventoryActions.swapWithHotbar(mc, slot, target)) {
				fail("Couldn't move the item to your hotbar");
				return;
			}
			slot = target;
		}
		if (inv.getSelectedSlot() != slot) inv.setSelectedSlot(slot);
		phase = Phase.PRE_SEND;
		phaseUntil = now + humanizer.handling();
		status = "Preparing listing";
	}

	private void tickPreSend(LocalPlayer player, AutoDonutConfig cfg, long now) {
		if (now < phaseUntil) return;
		ItemStack held = player.getMainHandItem();
		if (held.isEmpty() || !stillMatches(held)) {
			reset();
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

		if (!Compat.streamerMode()) player.sendSystemMessage(prefix().append(Component.literal("Listed " + held.getHoverName().getString()
				+ " x" + held.getCount() + " for " + PriceFormat.format(total)).withStyle(ChatFormatting.GRAY)));

		if (cfg.autoConfirm) {
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
		if (mc.gui.screen() instanceof AbstractContainerScreen<?> screen) {
			confirmScreen = screen;
			phase = Phase.CONFIRMING;
			// A human needs a moment to find the button.
			phaseUntil = now + humanizer.between(300, 850);
			status = "Confirming listing";
		} else if (now > phaseUntil) {
			// No confirmation menu appeared; the listing went through directly.
			phase = Phase.RESTORE;
			phaseUntil = now + humanizer.between(300, 900);
		}
	}

	private void tickConfirming(Minecraft mc, LocalPlayer player, long now) {
		if (now < phaseUntil) return;
		if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen) || screen != confirmScreen) {
			// The menu closed by itself.
			confirmScreen = null;
			phase = Phase.RESTORE;
			phaseUntil = now + humanizer.between(300, 900);
			return;
		}
		AbstractContainerMenu menu = screen.getMenu();
		int slot = findConfirmSlot(menu);
		if (slot >= 0 && InventoryActions.leftClick(mc, menu.containerId, slot)) {
			status = "Confirmed";
		} else {
			// Show the menu so the player can confirm by hand.
			confirmScreen = null;
			notifyPlayer(player, "Couldn't find the confirm button, please confirm the listing yourself.");
		}
		phase = Phase.RESTORE;
		phaseUntil = now + humanizer.between(700, 1400);
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

	private void tickRestore(Minecraft mc, LocalPlayer player, AutoDonutConfig cfg, long now) {
		if (now < phaseUntil) return;
		// Close a confirmation menu the server left open.
		if (confirmScreen != null && mc.gui.screen() == confirmScreen) player.closeContainer();
		confirmScreen = null;
		Inventory inv = player.getInventory();
		ItemStack held = player.getMainHandItem();

		// If the exact stack is still in hand, the server most likely refused the listing.
		boolean unchanged = !held.isEmpty() && ItemIndex.idOf(held.getItem()).equals(listedItemId) && held.getCount() == listedCount;
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

		if (previousSelected >= 0 && previousSelected != inv.getSelectedSlot()) {
			inv.setSelectedSlot(previousSelected);
		}
		nextAllowedAt = now + humanizer.nextListingDelay(cfg.minDelaySeconds, cfg.maxDelaySeconds, cfg.randomBreaks);
		reset();
	}

	/** Called for every system (non-player) chat message. */
	public void onGameMessage(Component message) {
		long now = System.currentTimeMillis();
		if (lastCommandAt == 0 || now - lastCommandAt > 6000) return;
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
