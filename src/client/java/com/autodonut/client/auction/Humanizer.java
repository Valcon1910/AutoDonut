package com.autodonut.client.auction;

import java.util.Random;

/**
 * Produces irregular, human-looking delays so actions never happen on a fixed rhythm.
 * Pure Java so it can be unit tested without Minecraft.
 */
public final class Humanizer {
	private final Random random;

	public Humanizer(Random random) {
		this.random = random;
	}

	/**
	 * A delay between {@code minMs} and {@code maxMs}, weighted towards the middle
	 * (average of two uniforms), plus a small jitter so values never repeat exactly.
	 */
	public long between(long minMs, long maxMs) {
		if (maxMs < minMs) maxMs = minMs;
		double u = (random.nextDouble() + random.nextDouble()) / 2.0;
		long base = minMs + Math.round(u * (maxMs - minMs));
		long jitter = Math.round((random.nextDouble() - 0.5) * Math.min(700, (maxMs - minMs) / 4.0 + 120));
		return Math.max(minMs, Math.min(maxMs, base + jitter));
	}

	/** Delay before the next listing. Occasionally adds a longer "break" if enabled. */
	public long nextListingDelay(int minSeconds, int maxSeconds, boolean breaks) {
		long delay = between(minSeconds * 1000L, maxSeconds * 1000L);
		if (breaks && random.nextDouble() < 0.08) {
			delay += between(45_000, 180_000);
		}
		return delay;
	}

	/** How long a "player" takes to notice a new item. */
	public long reaction(int maxSeconds) {
		return between(600, Math.max(900, maxSeconds * 1000L));
	}

	/** Short pause between switching hotbar slot and typing the command. */
	public long handling() {
		return between(280, 950);
	}
}
