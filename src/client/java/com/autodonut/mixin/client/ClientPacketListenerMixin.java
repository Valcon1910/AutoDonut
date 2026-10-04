package com.autodonut.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.multiplayer.ClientPacketListener;

import com.autodonut.client.ServerProbe;

/** Tells the server check when the server answers a ping. */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
	@Inject(method = "handlePongResponse", at = @At("HEAD"), require = 0)
	private void autodonut$onPong(CallbackInfo ci) {
		ServerProbe.onPong();
	}
}
