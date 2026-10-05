package com.autodonut.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.screens.Screen;

import com.autodonut.client.auction.AutoAuctionController;
import com.autodonut.client.auction.AutoBuyController;

/** Skips drawing auction menus while Auto Auction / Auto Buy click them in the background. */
@Mixin(Screen.class)
public abstract class ScreenMixin {
	@Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("HEAD"), cancellable = true, require = 0)
	private void autodonut$hideConfirmMenu(CallbackInfo ci) {
		Screen self = (Screen) (Object) this;
		if (AutoAuctionController.get().isHidden(self) || AutoBuyController.get().isHidden(self)) ci.cancel();
	}
}
