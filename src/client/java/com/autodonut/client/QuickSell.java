package com.autodonut.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

import com.autodonut.client.auction.InventoryActions;
import com.autodonut.client.config.AutoDonutConfig;
import com.autodonut.client.ui.AutoDonutScreen;
import com.autodonut.client.ui.UiSounds;
import com.autodonut.mixin.client.AbstractContainerScreenAccessor;

/**
 * Quick Sell (R): runs Donut's sell command so Donut opens its own auction interface where
 * you choose the price. Works in combat and inside inventories: with a container open it
 * sells the item under the mouse, otherwise the held item.
 */
public final class QuickSell {
	private static long lastUse;
	/** When a server-side slot switch should be undone (0 = nothing pending). */
	private static long restoreAt;

	private QuickSell() {
	}

	/** Whether R should act on this screen (not while typing). */
	public static boolean allowedOn(Screen screen) {
		if (screen == null) return true;
		if (screen instanceof ChatScreen || screen instanceof AutoDonutScreen) return false;
		return !(screen.getFocused() instanceof EditBox);
	}

	public static void trigger(Minecraft mc) {
		AutoDonutConfig cfg = AutoDonutConfig.get();
		LocalPlayer player = mc.player;
		if (!cfg.quickSellEnabled || player == null || Lockdown.active()) return;
		if (cfg.onlyOnDonut && !ServerContext.isOnDonut()) return;

		long now = System.currentTimeMillis();
		if (now - lastUse < 800) return;
		lastUse = now;

		Inventory inv = player.getInventory();
		int sel = inv.getSelectedSlot();
		Screen screen = mc.gui.screen();

		if (screen instanceof AbstractContainerScreen<?> container) {
			Slot hovered = ((AbstractContainerScreenAccessor) container).autodonut$getHoveredSlot();
			if (hovered == null || !hovered.hasItem() || !(hovered.container instanceof Inventory)) {
				hint(player, "Hover over an item in your inventory, then press R.");
				return;
			}
			int index = hovered.getContainerSlot();
			if (index >= 36) {
				hint(player, "Armor and off-hand items can't be quick sold.");
				return;
			}
			AbstractContainerMenu menu = container.getMenu();
			if (index == sel) {
				send(player, cfg);
			} else if (Inventory.isHotbarSlot(index)) {
				// Hotbar item: "hold" it on the server only; nothing moves on screen.
				player.connection.send(new ServerboundSetCarriedItemPacket(index));
				send(player, cfg);
				restoreAt = now + 1200;
			} else {
				// Main-inventory item: swap into the held slot, sell, swap back in the same tick.
				int menuSlot = menu.slots.indexOf(hovered);
				if (!InventoryActions.swapIn(mc, menu.containerId, menuSlot, sel)) return;
				send(player, cfg);
				InventoryActions.swapIn(mc, menu.containerId, menuSlot, sel);
			}
			return;
		}

		if (player.getMainHandItem().isEmpty()) {
			hint(player, "Hold the item you want to sell, then press R.");
			return;
		}
		send(player, cfg);
	}

	/** Undoes a pending server-side slot switch. Called every client tick. */
	public static void tick(Minecraft mc) {
		if (restoreAt == 0 || System.currentTimeMillis() < restoreAt) return;
		restoreAt = 0;
		if (mc.player != null) {
			mc.player.connection.send(new ServerboundSetCarriedItemPacket(mc.player.getInventory().getSelectedSlot()));
		}
	}

	private static void send(LocalPlayer player, AutoDonutConfig cfg) {
		String command = cfg.quickSellCommand.trim();
		if (command.startsWith("/")) command = command.substring(1);
		player.connection.sendCommand(command);
		UiSounds.click();
	}

	private static void hint(LocalPlayer player, String text) {
		if (Compat.streamerMode()) return;
		player.sendSystemMessage(Component.literal("[AutoDonut] ").withStyle(ChatFormatting.LIGHT_PURPLE)
				.append(Component.literal(text).withStyle(ChatFormatting.GRAY)));
	}
}
