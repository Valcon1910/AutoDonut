package com.autodonut.client;

import java.util.Locale;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;


/** Remembers which server the client is connecting to / playing on. */
public final class ServerContext {
	private static volatile String host;

	private ServerContext() {
	}

	/** Shown on the connect screen after "Encrypting..." when joining Donut SMP. */
	public static Component bootingMessage() {
		return Component.literal("AutoDonut Booting Up").withStyle(ChatFormatting.LIGHT_PURPLE);
	}

	public static void onConnect(String newHost) {
		host = newHost == null ? null : newHost.toLowerCase(Locale.ROOT);
	}

	public static void onDisconnect() {
		host = null;
	}

	public static String host() {
		return host;
	}

	public static boolean isOnDonut() {
		String h = host;
		return h != null && (h.equals("donutsmp.net") || h.endsWith(".donutsmp.net"));
	}
}
