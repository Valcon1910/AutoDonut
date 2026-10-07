package com.autodonut.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.autodonut.client.ui.LookHud;

/**
 * Stops Jade from picking a tooltip (instance tick) while AutoDonut's look-at HUD replaces it. Targets Jade by name
 * (it's optional) and every injection is require = 0, so a Jade update can never crash the game;
 * at worst both tooltips show.
 */
@Pseudo
@Mixin(targets = "snownee.jade.overlay.WailaTickHandler", remap = false)
public abstract class JadeTickMixin {
	@Inject(method = "tickClient", at = @At("HEAD"), cancellable = true, require = 0)
	private void autodonut$hideJade(CallbackInfo ci) {
		if (LookHud.active()) ci.cancel();
	}
}
