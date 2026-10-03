package com.autodonut.client.auction;

import java.lang.reflect.Method;


import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import com.autodonut.AutoDonut;

/** Inventory helpers that go through the normal client -> server click packets. */
public final class InventoryActions {
	private static Method clickMethod;
	private static Object swapType;
	private static boolean lookedUp;

	private InventoryActions() {
	}

	/**
	 * Swaps a main-inventory slot (9-35) with a hotbar slot (0-8), exactly like hovering
	 * the item in the inventory and pressing a number key.
	 * <p>
	 * The container-click method on MultiPlayerGameMode is found by its shape
	 * (int, int, int, click-type enum, Player) so this keeps working if Mojang renames it.
	 */
	public static boolean swapWithHotbar(Minecraft mc, int inventorySlot, int hotbarSlot) {
		LocalPlayer player = mc.player;
		if (player == null || mc.gameMode == null) return false;
		if (player.containerMenu != player.inventoryMenu) return false;
		if (!lookup(mc)) return false;
		try {
			// In the player inventory menu, main-inventory slots 9-35 keep the same index.
			clickMethod.invoke(mc.gameMode, player.inventoryMenu.containerId, inventorySlot, hotbarSlot, swapType, player);
			return true;
		} catch (ReflectiveOperationException | RuntimeException e) {
			AutoDonut.LOGGER.error("Inventory swap failed", e);
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
					swapType = Enum.valueOf((Class) t[3], "SWAP");
					clickMethod = m;
					return true;
				} catch (IllegalArgumentException ignored) {
					// Not the click method.
				}
			}
		}
		AutoDonut.LOGGER.warn("Could not find the container click method; items outside the hotbar won't be listed");
		return false;
	}
}
