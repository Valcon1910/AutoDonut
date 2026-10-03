package com.autodonut.client.auction;

import java.lang.reflect.Method;


import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import com.autodonut.AutoDonut;

/** Inventory helpers that go through the normal client -> server click packets. */
public final class InventoryActions {
	private static Method clickMethod;
	private static boolean lookedUp;

	private InventoryActions() {
	}

	/**
	 * Swaps a main-inventory slot (9-35) with a hotbar slot (0-8), exactly like hovering
	 * the item in the inventory and pressing a number key.
	 */
	public static boolean swapWithHotbar(Minecraft mc, int inventorySlot, int hotbarSlot) {
		LocalPlayer player = mc.player;
		if (player == null || player.containerMenu != player.inventoryMenu) return false;
		// In the player inventory menu, main-inventory slots 9-35 keep the same index.
		return click(mc, player.inventoryMenu.containerId, inventorySlot, hotbarSlot, "SWAP");
	}

	/**
	 * Swaps the item in a player-inventory menu slot with a hotbar slot (like pressing a number
	 * key over it). Used to put an item in the held slot for an instant and back again.
	 */
	public static boolean swap(Minecraft mc, int menuSlot, int hotbarSlot) {
		LocalPlayer player = mc.player;
		if (player == null || player.containerMenu != player.inventoryMenu) return false;
		return click(mc, player.inventoryMenu.containerId, menuSlot, hotbarSlot, "SWAP");
	}

	/** Whether inventory clicks can be sent (the click method was found). */
	public static boolean available(Minecraft mc) {
		return mc.gameMode == null ? !lookedUp || clickMethod != null : lookup(mc);
	}

	/** Swap (number-key) click on any open container: moves the slot's item to/from a hotbar slot. */
	public static boolean swapIn(Minecraft mc, int containerId, int menuSlot, int hotbarSlot) {
		return click(mc, containerId, menuSlot, hotbarSlot, "SWAP");
	}

	/** Left-clicks a slot of the open container, like a normal mouse click. */
	public static boolean leftClick(Minecraft mc, int containerId, int menuSlot) {
		return click(mc, containerId, menuSlot, 0, "PICKUP");
	}

	/**
	 * Sends a container click through MultiPlayerGameMode. The method is found by its shape
	 * (int, int, int, click-type enum, Player) so this keeps working if Mojang renames it.
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static boolean click(Minecraft mc, int containerId, int slot, int button, String type) {
		return clickRaw(mc, containerId, slot, button, type);
	}

	/** Normal click (button 0 = left, 1 = right) on a slot of the given container. */
	public static boolean click(Minecraft mc, int containerId, int slot, int button) {
		return clickRaw(mc, containerId, slot, button, "PICKUP");
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static boolean clickRaw(Minecraft mc, int containerId, int slot, int button, String type) {
		LocalPlayer player = mc.player;
		if (player == null || mc.gameMode == null || !lookup(mc)) return false;
		try {
			Object clickType = Enum.valueOf((Class) clickMethod.getParameterTypes()[3], type);
			clickMethod.invoke(mc.gameMode, containerId, slot, button, clickType, player);
			return true;
		} catch (ReflectiveOperationException | RuntimeException e) {
			AutoDonut.LOGGER.error("Container click failed", e);
			return false;
		}
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static boolean lookup(Minecraft mc) {
		if (lookedUp) return clickMethod != null;
		lookedUp = true;
		for (Method m : mc.gameMode.getClass().getMethods()) {
			Class<?>[] t = m.getParameterTypes();
			if (t.length == 5 && t[0] == int.class && t[1] == int.class && t[2] == int.class
					&& t[3].isEnum() && t[4].isAssignableFrom(LocalPlayer.class)) {
				try {
					Enum.valueOf((Class) t[3], "PICKUP");
					clickMethod = m;
					return true;
				} catch (IllegalArgumentException ignored) {
					// Not the click method.
				}
			}
		}
		AutoDonut.LOGGER.warn("Could not find the container click method");
		return false;
	}
}
