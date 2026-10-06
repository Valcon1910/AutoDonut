package com.autodonut.client;

import java.lang.reflect.Constructor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;

import com.autodonut.AutoDonut;
import com.autodonut.client.config.AutoDonutConfig;

/**
 * Checks that the server is still answering: every 10 seconds a tiny ping packet is sent and
 * the server's pong is awaited. Without an answer within 3 seconds the server counts as not
 * responding, and it is re-checked every 4 seconds until it answers again.
 */
public final class ServerProbe {
	private static final long INTERVAL_OK = 10_000;
	private static final long INTERVAL_BAD = 4_000;
	private static final long TIMEOUT = 3_000;

	private static Constructor<?> pingCtor;
	private static boolean lookedUp;
	/** Set once a pong has ever arrived, proving the pong hook works on this Minecraft version. */
	private static boolean hookWorks;

	private static boolean responding = true;
	private static long nextPingAt;
	private static long pendingSince = -1;
	private static long lastPongAt;

	private ServerProbe() {
	}

	/** False while the server isn't answering pings. */
	public static boolean responding() {
		return responding;
	}

	public static void reset() {
		responding = true;
		pendingSince = -1;
		nextPingAt = System.currentTimeMillis() + INTERVAL_OK;
	}

	/** Called by the client mixin whenever a pong arrives. */
	public static void onPong() {
		hookWorks = true;
		lastPongAt = System.currentTimeMillis();
		pendingSince = -1;
		responding = true;
	}

	/**
	 * Pings right away (unless one is already on its way) for an on-demand lag check. Returns false
	 * when pings can't tell anything on this version, so the caller shouldn't wait for an answer.
	 */
	public static boolean pingNow(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null) return false;
		long now = System.currentTimeMillis();
		if (pendingSince < 0) {
			if (!send(player, now)) return false;
			pendingSince = now;
		}
		return hookWorks;
	}

	/** Whether a pong arrived at or after the given moment. */
	public static boolean answeredSince(long time) {
		return lastPongAt >= time;
	}

	public static void tick(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null || !AutoDonutConfig.get().serverCheck || !ServerContext.isOnDonut()) {
			responding = true;
			pendingSince = -1;
			return;
		}
		long now = System.currentTimeMillis();
		if (pendingSince >= 0 && now - pendingSince > TIMEOUT) {
			pendingSince = -1;
			// Only trust a missing answer once the hook has seen at least one pong.
			if (hookWorks) responding = false;
			nextPingAt = now + INTERVAL_BAD;
		}
		if (pendingSince < 0 && now >= nextPingAt) {
			if (send(player, now)) {
				pendingSince = now;
				nextPingAt = now + (responding ? INTERVAL_OK : INTERVAL_BAD);
			} else {
				nextPingAt = now + INTERVAL_OK;
			}
		}
	}

	private static boolean send(LocalPlayer player, long now) {
		if (!lookedUp) {
			lookedUp = true;
			try {
				Class<?> cls = Class.forName("net.minecraft.network.protocol.ping.ServerboundPingRequestPacket");
				pingCtor = cls.getConstructor(long.class);
			} catch (ReflectiveOperationException | LinkageError e) {
				AutoDonut.LOGGER.warn("Server check unavailable: ping packet not found");
			}
		}
		if (pingCtor == null) return false;
		try {
			player.connection.send((Packet<?>) pingCtor.newInstance(now));
			return true;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return false;
		}
	}
}
