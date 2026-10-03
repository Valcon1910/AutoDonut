package com.autodonut.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import com.autodonut.client.config.AutoDonutConfig;
import com.autodonut.client.ui.UiSounds;

/**
 * Quick Sell (R): runs Donut's sell command for the item in your hand, so Donut opens its own
 * auction interface where you choose the price.
 */
public final class QuickSell {
	private static long lastUse;

	private QuickSell() {
	}

	public static void trigger(Minecraft mc) {
		AutoDonutConfig cfg = AutoDonutConfig.get();
		LocalPlayer player = mc.player;
		if (!cfg.quickSellEnabled || player == null) return;
		if (cfg.onlyOnDonut && !ServerContext.isOnDonut()) return;

		long now = System.currentTimeMillis();
		if (now - lastUse < 800) return;
		lastUse = now;

		ItemStack held = player.getMainHandItem();
		if (held.isEmpty()) {
			if (!Compat.streamerMode()) {
				player.sendSystemMessage(Component.literal("[AutoDonut] ").withStyle(ChatFormatting.LIGHT_PURPLE)
						.append(Component.literal("Hold the item you want to sell, then press R.").withStyle(ChatFormatting.GRAY)));
			}
			return;
		}
		String command = cfg.quickSellCommand.trim();
		if (command.startsWith("/")) command = command.substring(1);
		player.connection.sendCommand(command);
		UiSounds.click();
	}
}
