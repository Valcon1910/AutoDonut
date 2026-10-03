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

		boolean show = cfg.showHud && !Compat.streamerMode() && auction.isActive() && mc.gui.screen() == null && mc.player != null;
		VISIBLE.set(show ? 1 : 0);
		float v = VISIBLE.update(UI.dt);
		if (v <= 0.01f) return;

		UI.g = graphics;
		UI.font = mc.font;
		UI.theme = Theme.current(cfg);
		UI.alpha = v * 0.92f;

		String label = "Auto Auction";
		String status = auction.status();
		int w = 18 + UI.width(label) + 8 + UI.width(status) + 8;
		// Leave room for a top-left minimap (Xaero's, JourneyMap, VoxelMap).
		int baseX = Compat.hasMinimap() ? Math.min(150, graphics.guiWidth() / 3) : 6;
		int x = baseX - Math.round((1f - v) * 10);
		int y = 6;
		UI.card(x, y, w, 16, 8, UI.theme.panel(), UI.theme.border());
		UI.circle(x + 9, y + 8, 3, UI.theme.accent());
		UI.text(label, x + 17, y + 4, UI.theme.text());
		UI.text(status, x + 17 + UI.width(label) + 8, y + 4, UI.theme.textMuted());
	}
}
