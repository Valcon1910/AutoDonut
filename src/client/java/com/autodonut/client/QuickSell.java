package com.autodonut.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
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
import com.autodonut.client.auction.AutoAuctionController;
import com.autodonut.client.ui.QuickSellScreen;
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
		// Only inventories and chests, where there's an item to hover. Signs, books, chat and other
		// text screens type their own way (not always through an EditBox), so R stays a letter there.
		if (!(screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>)) return false;
		return !(screen.getFocused() instanceof EditBox);
	}

	public static void trigger(Minecraft mc) {
		AutoDonutConfig cfg = AutoDonutConfig.get();
		LocalPlayer player = mc.player;
		if (!cfg.quickSellEnabled || player == null || Lockdown.active()) return;
		if (cfg.onlyOnDonut && !ServerContext.isOnDonut()) return;
		if (AutoAuctionController.get().inCombat()) {
			hint(player, "You're in combat, Quick Sell is unavailable until it ends.");
			return;
		}

		long now = System.currentTimeMillis();
		if (now - lastUse < 400) return;
		lastUse = now;

		Inventory inv = player.getInventory();
		Screen screen = mc.gui.screen();
		int index;
		if (screen instanceof AbstractContainerScreen<?> container) {
			Slot hovered = ((AbstractContainerScreenAccessor) container).autodonut$getHoveredSlot();
			if (hovered == null || !hovered.hasItem() || !(hovered.container instanceof Inventory)) {
				hint(player, "Hover over an item in your inventory, then press " + AutoDonutClient.quickSellKeyName() + ".");
				return;
			}
			index = hovered.getContainerSlot();
			if (index >= 36) {
				hint(player, "Armor and off-hand items can't be quick sold.");
				return;
			}
			// Close the container properly before showing the price prompt.
			player.closeContainer();
		} else {
			index = inv.getSelectedSlot();
			if (inv.getItem(index).isEmpty()) {
				hint(player, "Hold the item you want to sell, then press " + AutoDonutClient.quickSellKeyName() + ".");
				return;
			}
		}
		mc.gui.setScreen(new QuickSellScreen(index, inv.getItem(index)));
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
