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

		// Version pill
		String version = "AutoDonut v" + AutoDonutClient.version();
		int pw = UI.width(version) + 12;
		int px = x - 4 - pw;
		int pill = textHovered ? t.surfaceHover() : t.panel();
		UI.round(px, y + 3, pw, SIZE - 6, 3, (pill & 0x00FFFFFF) | 0xE0000000);
		UI.text(version, px + 6, y + (SIZE - 8) / 2 + 1, textHovered ? t.text() : t.textMuted());

		// Logo tile: lifts and gets an accent outline on hover
		int lift = Math.round(h);
		UI.round(x, y + 1, SIZE, SIZE, 4, 0x55000000);
		int bg = Anim.lerpColor(t.panel(), t.surfaceHover(), h);
		UI.round(x, y - lift, SIZE, SIZE, 4, bg);
		UI.outline(x, y - lift, SIZE, SIZE, 1, Anim.lerpColor(t.border(), t.accent(), h));
		UI.logo(x + 3, y + 3 - lift, SIZE - 6);

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
