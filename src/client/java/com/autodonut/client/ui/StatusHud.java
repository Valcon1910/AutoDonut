package com.autodonut.client.ui;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import com.autodonut.client.Compat;
import com.autodonut.client.auction.AutoAuctionController;
import com.autodonut.client.auction.AutoBuyController;
import com.autodonut.client.config.AutoDonutConfig;

/** Small pill in the top-left corner showing what Auto Auction is doing. */
public final class StatusHud {
	private static final Ui UI = new Ui();
	private static final Anim VISIBLE = new Anim(0, 8);
	private static final Anim EXPANDED = new Anim(0, 10);
	private static long lastFrame = System.nanoTime();
	/** The folded badge hides after this long without anything happening. */
	private static final long BADGE_IDLE_MS = 10_000;
	private static long lastActivity = System.currentTimeMillis();

	private StatusHud() {
	}

	public static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		AutoDonutConfig cfg = AutoDonutConfig.get();
		AutoAuctionController auction = AutoAuctionController.get();
		AutoBuyController buy = AutoBuyController.get();

		long now = System.nanoTime();
		UI.dt = Math.min(0.1f, (now - lastFrame) / 1_000_000_000f);
		lastFrame = now;

		boolean noResponse = !com.autodonut.client.ServerProbe.responding();
		boolean allowed = cfg.showHud && !Compat.streamerMode() && !com.autodonut.client.Lockdown.active() && mc.player != null;
		boolean lagWarning = auction.lagging() || noResponse;
		boolean wanted = allowed && (auction.isActive() || buy.isActive() || auction.quickSelling() || lagWarning);
		// Full label only with no screen open and something to show; otherwise it folds to the logo badge,
		// which stays for 10 seconds after the last activity and then fades away.
		// A menu being clicked in the background is invisible, so it counts as no screen.
		net.minecraft.client.gui.screens.Screen open = mc.gui.screen();
		boolean expanded = wanted && (open == null || auction.isHidden(open) || buy.isHidden(open));
		long ms = System.currentTimeMillis();
		if (expanded || auction.busy() || buy.busy() || auction.quickSelling() || lagWarning) lastActivity = ms;
		boolean active = allowed && (expanded || ms - lastActivity <= BADGE_IDLE_MS);
		// Stay out of the way in PvP; only the lag warning may show.
		if (auction.inCombat() && !lagWarning) active = false;
		expanded = expanded && active;
		VISIBLE.set(active ? 1 : 0);
		EXPANDED.set(expanded ? 1 : 0);
		float v = VISIBLE.update(UI.dt);
		float e = Anim.easeInOut(EXPANDED.update(UI.dt));
		if (v <= 0.01f) return;

		UI.g = graphics;
		UI.font = mc.font;
		UI.theme = Theme.current(cfg);

		// Auto Buy takes the label while it's working, or when it's the only feature running.
		boolean showBuy = !auction.quickSelling() && !auction.busy()
				&& (buy.busy() || (buy.isActive() && !auction.isActive()));
		// Lag found by AutoDonut's own checks while no feature is in the middle of something is
		// shown as a system message rather than under a feature's name.
		boolean system = (noResponse || auction.lagging()) && !auction.busy() && !auction.quickSelling() && !buy.busy();
		String label = system ? "System" : showBuy ? "Auto Buy" : "Auto Auction";
		String status = system ? (noResponse ? "Server not responding (Lag)" : auction.lagReason())
				: noResponse ? "Server not responding (Lag)" : showBuy ? buy.status() : auction.status();
		int fullW = 18 + UI.width(label) + 8 + UI.width(status) + 8;
		int w = 18 + Math.round((fullW - 18) * e);
		// Leave room for a top-left minimap (Xaero's, JourneyMap, VoxelMap).
		// Slides left as it fades out, and a little left again when it folds into the badge.
		int x = Compat.hudX() - Math.round((1f - v) * 10) - Math.round((1f - e) * 6);
		int y = 6;
		UI.alpha = v * 0.92f;
		UI.card(x, y, w, 16, 8, UI.theme.panel(), UI.theme.border());

		// The logo image can't fade, so it shrinks away into its centre instead.
		UI.alpha = 1f;
		int size = Math.round(12 * Anim.easeInOut(v));
		if (size >= 2) UI.logo(x + 9 - size / 2, y + 8 - size / 2, size);

		if (e > 0.05f) {
			UI.alpha = v * 0.92f * e;
			UI.scissor(x, y, x + w - 4, y + 16);
			UI.text(label, x + 17, y + 4, UI.theme.text());
			UI.text(status, x + 17 + UI.width(label) + 8, y + 4, auction.lagging() || noResponse ? Ui.WARNING : UI.theme.textMuted());
			UI.endScissor();
		}
	}
}
