package com.autodonut.client;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import com.autodonut.client.auction.AutoAuctionController;
import com.autodonut.client.auction.ItemIndex;
import com.autodonut.client.config.AutoDonutConfig;

/**
 * The "AutoDonut Booting Up" sequence on the connect screen. Each step does its real work
 * when its turn comes, then its line fades in under the status text.
 */
public final class BootSequence {
	private record Step(String label, Runnable work) { }

	private static final long STEP_MS = 160;
	private static final List<Step> STEPS = List.of(
			new Step("Loading settings", AutoDonutConfig::load),
			new Step("Indexing items", () -> ItemIndex.byId("minecraft:stone")),
			new Step("Preparing Auto Auction", () -> AutoAuctionController.get().onDisconnect()),
			new Step("Safety checks ready", () -> { }),
			new Step("Ready", () -> { })
	);

	private static long startedAt = -1;
	private static int done;

	private BootSequence() {
	}

	public static void start() {
		if (startedAt < 0) {
			startedAt = System.currentTimeMillis();
			done = 0;
		}
	}

	public static void reset() {
		startedAt = -1;
		done = 0;
	}

	/** Draws the completed steps below the connect screen's status line. */
	public static void render(GuiGraphicsExtractor graphics) {
		if (startedAt < 0) return;
		long elapsed = System.currentTimeMillis() - startedAt;
		while (done < STEPS.size() && elapsed >= (done + 1) * STEP_MS) {
			STEPS.get(done).work().run();
			done++;
		}
		Minecraft mc = Minecraft.getInstance();
		int cx = graphics.guiWidth() / 2;
		int y = graphics.guiHeight() / 2 - 30;
		for (int i = 0; i < done; i++) {
			Step step = STEPS.get(i);
			float fade = Math.min(1f, (elapsed - (i + 1) * STEP_MS) / 200f);
			int alpha = Math.max(8, Math.round(fade * 255));
			boolean last = i == STEPS.size() - 1;
			String text = (last ? "" : "✔ ") + step.label();
			int color = last ? 0xE8689F : 0x9AA0AA;
			int lineY = y + i * 11 + Math.round((1f - fade) * 4);
			graphics.text(mc.font, text, cx - mc.font.width(text) / 2, lineY, (alpha << 24) | color, false);
		}
	}
}
