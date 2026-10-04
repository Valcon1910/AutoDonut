package com.autodonut.client.ui;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import com.autodonut.client.Compat;
import com.autodonut.client.auction.AutoAuctionController;
import com.autodonut.client.config.AutoDonutConfig;

/** Small pill in the top-left corner showing what Auto Auction is doing. */
public final class StatusHud {
	private static final Ui UI = new Ui();
	private static final Anim VISIBLE = new Anim(0, 8);
	private static final Anim EXPANDED = new Anim(0, 10);
	private static long lastFrame = System.nanoTime();

	private StatusHud() {
	}

	public static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		AutoDonutConfig cfg = AutoDonutConfig.get();
		AutoAuctionController auction = AutoAuctionController.get();

		long now = System.nanoTime();
		UI.dt = Math.min(0.1f, (now - lastFrame) / 1_000_000_000f);
		lastFrame = now;

		boolean noResponse = !com.autodonut.client.ServerProbe.responding();
		boolean active = cfg.showHud && !Compat.streamerMode() && !com.autodonut.client.Lockdown.active()
				&& (auction.isActive() || noResponse) && mc.player != null;
		// With a screen open (inventory, chat, ...) the label folds down to just the logo badge.
		boolean expanded = active && mc.gui.screen() == null;
		VISIBLE.set(active ? 1 : 0);
		EXPANDED.set(expanded ? 1 : 0);
		float v = VISIBLE.update(UI.dt);
		float e = Anim.easeInOut(EXPANDED.update(UI.dt));
		if (v <= 0.01f) return;

		UI.g = graphics;
		UI.font = mc.font;
		UI.theme = Theme.current(cfg);

		String label = "Auto Auction";
		String status = noResponse ? "Server not responding (Lag)" : auction.status();
		int fullW = 18 + UI.width(label) + 8 + UI.width(status) + 8;
		int w = 18 + Math.round((fullW - 18) * e);
		// Leave room for a top-left minimap (Xaero's, JourneyMap, VoxelMap).
		// Slides left as it fades out, and a little left again when it folds into the badge.
		int x = Compat.hudX() - Math.round((1f - v) * 10) - Math.round((1f - e) * 6);
		int y = 6;
		UI.alpha = v * 0.92f;
		UI.card(x, y, w, 16, 8, UI.theme.panel(), UI.theme.border());

		// The logo image can't fade, so it stays drawn and the panel's dark shade is laid over it.
		UI.alpha = 1f;
		UI.logo(x + 3, y + 2, 12);
		int shade = Math.round((1f - v) * 255);
		if (shade > 0) UI.round(x + 3, y + 2, 12, 12, 6, (shade << 24) | (UI.theme.panel() & 0x00FFFFFF));

		if (e > 0.05f) {
			UI.alpha = v * 0.92f * e;
			UI.scissor(x, y, x + w - 4, y + 16);
			UI.text(label, x + 17, y + 4, UI.theme.text());
			UI.text(status, x + 17 + UI.width(label) + 8, y + 4, auction.lagging() || noResponse ? Ui.WARNING : UI.theme.textMuted());
			UI.endScissor();
		}
	}
}
