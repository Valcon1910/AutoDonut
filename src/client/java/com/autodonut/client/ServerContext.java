package com.autodonut.client;

import java.util.Locale;

import net.minecraft.network.chat.Component;


/** Remembers which server the client is connecting to / playing on. */
public final class ServerContext {
	private static volatile String host;
	/** Host of the connection attempt in progress; only becomes {@link #host} once joined. */
	private static volatile String pendingHost;

	private ServerContext() {
	}

	/** Shown on the connect screen after "Encrypting..." when joining Donut SMP. */
	public static Component bootingMessage() {
		return Component.literal("AutoDonut Booting Up");
	}

	public static void onConnect(String newHost) {
		pendingHost = newHost == null ? null : newHost.toLowerCase(Locale.ROOT);
		// The connect screen is showing the boot lines for this server already.
		host = pendingHost;
	}

	/** A world was joined: confirm the server, or clear it for singleplayer. */
	public static void onJoin(boolean singleplayer) {
		host = singleplayer ? null : pendingHost;
	}

	/** Not in a world right now (title screen, failed connect, loading between servers). */
	public static void onNotInWorld() {
		host = null;
	}

	public static void onDisconnect() {
		host = null;
		pendingHost = null;
	}

	public static String host() {
		return host;
	}

	public static boolean isOnDonut() {
		String h = host;
		return h != null && (h.equals("donutsmp.net") || h.endsWith(".donutsmp.net"));
	}
}
