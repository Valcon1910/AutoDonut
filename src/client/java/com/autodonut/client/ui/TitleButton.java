package com.autodonut.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;

import com.autodonut.client.AutoDonutClient;
import com.autodonut.client.config.AutoDonutConfig;

/**
 * AutoDonut button in the bottom-right corner of the title screen, drawn in the panel's own
 * style: a themed rounded tile with the donut logo, the version in a pill to its left, and an
 * "AutoDonut" tooltip. An invisible vanilla button underneath handles clicks and keyboard focus.
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
		int y = height - SIZE - 14; // just above Mojang's copyright line
		Button button = Button.builder(Component.literal("AutoDonut"), b -> {
			UiSounds.click();
			client.gui.setScreen(new AutoDonutScreen(screen));
		}).bounds(x, y, SIZE, SIZE).build();
		button.setAlpha(0f);
		Screens.getWidgets(screen).add(button);

		ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) ->
				draw(client, graphics, x, y, mouseX >= x && mouseX < x + SIZE && mouseY >= y && mouseY < y + SIZE));
	}

	private static void draw(Minecraft client, GuiGraphicsExtractor graphics, int x, int y, boolean hovered) {
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
		UI.round(px, y + 3, pw, SIZE - 6, 3, (t.panel() & 0x00FFFFFF) | 0xE0000000);
		UI.text(version, px + 6, y + (SIZE - 8) / 2 + 1, t.textMuted());

		// Logo tile: lifts and gets an accent outline on hover
		int lift = Math.round(h);
		UI.round(x, y + 1, SIZE, SIZE, 4, 0x55000000);
		int bg = Anim.lerpColor(t.panel(), t.surfaceHover(), h);
		UI.round(x, y - lift, SIZE, SIZE, 4, bg);
		UI.outline(x, y - lift, SIZE, SIZE, 1, Anim.lerpColor(t.border(), t.accent(), h));
		UI.logo(x + 3, y + 3 - lift, SIZE - 6);

		if (hovered) {
			UI.tooltip = "AutoDonut";
			UI.tooltipWarning = false;
			UI.tooltipX = x - 30;
			UI.tooltipY = y - 24;
			UI.drawTooltip(0, 0, graphics.guiWidth(), graphics.guiHeight());
		}
	}
}
