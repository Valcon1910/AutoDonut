package com.autodonut.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.screens.Screen;

import com.autodonut.client.auction.AutoAuctionController;

/** Skips drawing the auction confirm menu while Auto Auction confirms it in the background. */
@Mixin(Screen.class)
public abstract class ScreenMixin {
	@Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("HEAD"), cancellable = true, require = 0)
	private void autodonut$hideConfirmMenu(CallbackInfo ci) {
		if (AutoAuctionController.get().isHidden((Screen) (Object) this)) ci.cancel();
	}
}
