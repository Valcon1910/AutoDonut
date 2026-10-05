package com.autodonut.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;

import com.autodonut.client.AutoDonutClient;
import com.autodonut.client.config.AutoDonutConfig;

/**
 * AutoDonut button in the top-right corner of the title screen, drawn in the panel's own
 * style: a themed rounded tile with the donut logo, the version in a pill to its left. A click area underneath handles the click; it draws nothing itself, so the title screen's fade-in and focus outline never show through.
 */
public final class TitleButton {
	private static final int SIZE = 20;
	private static final Ui UI = new Ui();
	private static final Anim HOVER = new Anim(0, 16);
	private static long lastFrame = System.nanoTime();
	/** When the title screen first showed this game session (-1 = not yet); drives the intro. */
	private static long introStart = -1;
	private static final float INTRO_DELAY_MS = 350f;
	private static final float INTRO_MS = 650f;

	private TitleButton() {
	}

	public static void add(Minecraft client, Screen screen, int width, int height) {
		int x = width - SIZE - 4;
		int y = 4; // top-right corner
		Screens.getWidgets(screen).add(new HitArea(x, y, SIZE, SIZE, () -> {
			UiSounds.click();
			client.gui.setScreen(new AutoDonutScreen(screen));
		}));
		// The "AutoDonut vX" text opens the changelog window.
		int pw = client.font.width("AutoDonut v" + AutoDonutClient.version()) + 12;
		int px = x - 4 - pw;
		Screens.getWidgets(screen).add(new HitArea(px, y + 3, pw, SIZE - 6, () -> {
			UiSounds.click();
			client.gui.setScreen(new ChangelogScreen(screen));
		}));

		ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> {
			draw(client, graphics, x, y, mouseX >= x && mouseX < x + SIZE && mouseY >= y && mouseY < y + SIZE,
					mouseX >= px && mouseX < px + pw && mouseY >= y + 3 && mouseY < y + SIZE - 3);
		});
	}

	private static void draw(Minecraft client, GuiGraphicsExtractor graphics, int x, int y, boolean hovered, boolean textHovered) {
		long now = System.nanoTime();
		UI.dt = Math.min(0.1f, (now - lastFrame) / 1_000_000_000f);
		lastFrame = now;
		if (hovered && HOVER.target() < 0.5f) UiSounds.hover();
		HOVER.set(hovered ? 1 : 0);
		float h = HOVER.update(UI.dt);

		UI.g = graphics;
		UI.font = client.font;
		UI.theme = Theme.current(AutoDonutConfig.get());
		UI.alpha = 1f;
		Theme t = UI.theme;

		// Intro once per game start: the tile drops in, then the version pill slides out beside it.
		if (introStart < 0) introStart = now;
		float elapsed = (now - introStart) / 1_000_000f - INTRO_DELAY_MS;
		float tileIn = Anim.easeInOut(Math.max(0f, Math.min(1f, elapsed / INTRO_MS)));
		float pillIn = Anim.easeInOut(Math.max(0f, Math.min(1f, (elapsed - INTRO_MS * 0.45f) / INTRO_MS)));
		if (tileIn <= 0f) return;

		// Version pill
		String version = "AutoDonut v" + AutoDonutClient.version();
		int pw = UI.width(version) + 12;
		int px = x - 4 - pw + Math.round((1f - pillIn) * 16);
		int pill = textHovered ? t.surfaceHover() : t.panel();
		UI.alpha = pillIn;
		if (pillIn > 0.02f) {
			UI.round(px, y + 3, pw, SIZE - 6, 3, (pill & 0x00FFFFFF) | 0xE0000000);
			UI.text(version, px + 6, y + (SIZE - 8) / 2 + 1, textHovered ? t.text() : t.textMuted());
		}
		UI.alpha = tileIn;
		y -= Math.round((1f - tileIn) * 10);

		// Logo tile: lifts and gets an accent outline on hover
		int lift = Math.round(h);
		UI.round(x, y + 1, SIZE, SIZE, 4, 0x55000000);
		int bg = Anim.lerpColor(t.panel(), t.surfaceHover(), h);
		UI.round(x, y - lift, SIZE, SIZE, 4, bg);
		UI.outline(x, y - lift, SIZE, SIZE, 1, Anim.lerpColor(t.border(), t.accent(), h));
		// The logo image can't fade, so it grows in from its centre with the tile.
		UI.alpha = 1f;
		int size = Math.round((SIZE - 6) * tileIn);
		if (size >= 2) UI.logo(x + SIZE / 2 - size / 2, y + SIZE / 2 - lift - size / 2, size);

	}

	/** Clickable area with no visuals of its own (everything is drawn by {@link #draw}). */
	private static final class HitArea extends AbstractButton {
		private final Runnable action;

		HitArea(int x, int y, int w, int h, Runnable action) {
			super(x, y, w, h, Component.literal("AutoDonut"));
			this.action = action;
			super.setAlpha(0f);
		}

		/** The title screen fades every widget in by setting its alpha; this one always stays invisible. */
		@Override
		public void setAlpha(float alpha) {
			super.setAlpha(0f);
		}

		@Override
		public void onPress(InputWithModifiers input) {
			action.run();
		}

		@Override
		protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
			// Intentionally empty: the themed tile is drawn by TitleButton.draw.
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
		}
	}
}
