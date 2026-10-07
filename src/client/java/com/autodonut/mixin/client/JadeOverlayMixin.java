package com.autodonut.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.autodonut.client.ui.LookHud;

/**
 * Hides Jade's own tooltip while AutoDonut's look-at HUD replaces it. Targets Jade by name
 * (it's optional) and every injection is require = 0, so a Jade update can never crash the game;
 * at worst both tooltips show.
 */
@Pseudo
@Mixin(targets = "snownee.jade.overlay.OverlayRenderer", remap = false)
public abstract class JadeOverlayMixin {
	@Inject(method = {"renderOverlay478757", "renderOverlay"}, at = @At("HEAD"), cancellable = true, require = 0)
	private static void autodonut$hideJade(CallbackInfo ci) {
		if (LookHud.active()) ci.cancel();
	}
}
