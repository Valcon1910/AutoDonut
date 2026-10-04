package com.autodonut.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;

import com.autodonut.client.AutoDonutClient;
import com.autodonut.client.config.AutoDonutConfig;

/** Donut button in the bottom-right corner of the title screen that opens the AutoDonut panel. */
public final class TitleButton {
	private static final int SIZE = 20;
	private static final Ui UI = new Ui();

	private TitleButton() {
	}

	public static void add(Minecraft client, Screen screen, int width, int height) {
		int x = width - SIZE - 4;
		int y = height - SIZE - 14; // just above Mojang's copyright line
		Button button = Button.builder(Component.empty(), b -> client.gui.setScreen(new AutoDonutScreen(screen)))
				.bounds(x, y, SIZE, SIZE)
				.tooltip(Tooltip.create(Component.literal("AutoDonut")))
				.build();
		Screens.getWidgets(screen).add(button);

		ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> draw(client, graphics, x, y));
	}

	private static void draw(Minecraft client, GuiGraphicsExtractor graphics, int x, int y) {
		UI.g = graphics;
		UI.font = client.font;
		UI.theme = Theme.current(AutoDonutConfig.get());
		UI.alpha = 1f;
		UI.logo(x + 3, y + 3, SIZE - 6);
		String version = "AutoDonut v" + AutoDonutClient.version();
		graphics.text(client.font, version, x - 6 - client.font.width(version), y + (SIZE - 8) / 2, 0xFFFFFFFF, true);
	}
}
