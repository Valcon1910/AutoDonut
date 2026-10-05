package com.autodonut.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;

import com.autodonut.client.auction.AutoBuyController;

/**
 * Lets Auto Buy keep the auction menus it works in from opening on screen ("phantom" menus),
 * so the player can keep moving. require = 0: if this ever stops applying, Auto Buy falls back
 * to drawing nothing for those menus (see ScreenMixin).
 */
@Mixin(Gui.class)
public abstract class GuiMixin {
	@Inject(method = "setScreen", at = @At("HEAD"), cancellable = true, require = 0)
	private void autodonut$phantomMenus(Screen screen, CallbackInfo ci) {
		if (AutoBuyController.get().onSetScreen(screen)) ci.cancel();
	}
}
