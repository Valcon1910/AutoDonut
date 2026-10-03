package com.autodonut.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import com.autodonut.client.BootSequence;
import com.autodonut.client.ServerContext;

/**
 * Remembers which server we are joining, and on Donut SMP replaces the status shown after
 * "Encrypting..." with "AutoDonut Booting Up". Injectors use require = 0 so a future
 * Minecraft change can only hide the message, never crash the game.
 */
@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin {
	@Unique
	private boolean autodonut$encrypted;

	@ModifyVariable(method = "startConnecting", at = @At("HEAD"), argsOnly = true, require = 0)
	private static ServerAddress autodonut$captureAddress(ServerAddress address) {
		ServerContext.onConnect(address.getHost());
		BootSequence.reset();
		return address;
	}

	@ModifyVariable(method = "updateStatus", at = @At("HEAD"), argsOnly = true, require = 0)
	private Component autodonut$bootMessage(Component status) {
		if (!ServerContext.isOnDonut()) return status;
		if (status.getContents() instanceof TranslatableContents translatable
				&& "connect.encrypting".equals(translatable.getKey())) {
			autodonut$encrypted = true;
			BootSequence.start();
			return status;
		}
		if (!autodonut$encrypted) return status;
		BootSequence.start();
		return ServerContext.bootingMessage();
	}

	@Inject(method = "extractRenderState", at = @At("TAIL"), require = 0)
	private void autodonut$bootLines(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		if (autodonut$encrypted && ServerContext.isOnDonut()) BootSequence.render(graphics);
	}
}
