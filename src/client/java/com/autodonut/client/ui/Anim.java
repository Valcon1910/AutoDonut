package com.autodonut.client.ui;

/** A value that eases smoothly toward a target, frame-rate independent. */
public final class Anim {
	private float value;
	private float target;
	private final float speed;

	public Anim(float initial, float speed) {
		this.value = initial;
		this.target = initial;
		this.speed = speed;
	}

	public void set(float target) {
		this.target = target;
	}

	public void snap(float v) {
		this.value = v;
		this.target = v;
	}

	public float update(float dt) {
		value += (target - value) * (1f - (float) Math.exp(-speed * dt));
		if (Math.abs(target - value) < 0.0015f) value = target;
		return value;
	}

	public float get() {
		return value;
	}

	public float target() {
		return target;
	}

	public static float easeOutCubic(float t) {
		t = clamp01(t);
		float u = 1f - t;
		return 1f - u * u * u;
	}

	public static float easeInCubic(float t) {
		t = clamp01(t);
		return t * t * t;
	}

	public static float easeInOut(float t) {
		t = clamp01(t);
		return t < 0.5f ? 4f * t * t * t : 1f - (float) Math.pow(-2f * t + 2f, 3) / 2f;
	}

	public static float clamp01(float t) {
		return t < 0 ? 0 : (t > 1 ? 1 : t);
	}

	public static int lerpColor(int a, int b, float t) {
		t = clamp01(t);
		int aa = a >>> 24, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
		int ba = b >>> 24, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
		return (Math.round(aa + (ba - aa) * t) << 24)
				| (Math.round(ar + (br - ar) * t) << 16)
				| (Math.round(ag + (bg - ag) * t) << 8)
				| Math.round(ab + (bb - ab) * t);
	}
}
