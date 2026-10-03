package com.autodonut.client.ui;

/** Dark and light colour palettes. The screen blends between them for the theme transition. */
public record Theme(
		int scrim,
		int panel,
		int sidebar,
		int surface,
		int surfaceHover,
		int border,
		int text,
		int textMuted,
		int accent,
		int accentHover,
		int onAccent,
		int track,
		int knob,
		int danger,
		int success,
		int shadow
) {
	public static final Theme DARK = new Theme(
			0x99000000,
			0xFF16181D,
			0xFF111317,
			0xFF1F2229,
			0xFF282C35,
			0xFF2C3039,
			0xFFE9EAEE,
			0xFF8C919B,
			0xFFE8689F,
			0xFFF07DAE,
			0xFFFFFFFF,
			0xFF343844,
			0xFFFFFFFF,
			0xFFE5484D,
			0xFF3DD68C,
			0x66000000
	);

	public static final Theme LIGHT = new Theme(
			0x55101216,
			0xFFF8F8FA,
			0xFFEFF0F4,
			0xFFFFFFFF,
			0xFFF1F2F6,
			0xFFDDDFE5,
			0xFF1B1D22,
			0xFF6B7080,
			0xFFD8578E,
			0xFFE0689C,
			0xFFFFFFFF,
			0xFFD5D8DF,
			0xFFFFFFFF,
			0xFFD93D42,
			0xFF1F9D5C,
			0x33000000
	);

	public static Theme lerp(Theme a, Theme b, float t) {
		if (t <= 0) return a;
		if (t >= 1) return b;
		return new Theme(
				Anim.lerpColor(a.scrim, b.scrim, t),
				Anim.lerpColor(a.panel, b.panel, t),
				Anim.lerpColor(a.sidebar, b.sidebar, t),
				Anim.lerpColor(a.surface, b.surface, t),
				Anim.lerpColor(a.surfaceHover, b.surfaceHover, t),
				Anim.lerpColor(a.border, b.border, t),
				Anim.lerpColor(a.text, b.text, t),
				Anim.lerpColor(a.textMuted, b.textMuted, t),
				Anim.lerpColor(a.accent, b.accent, t),
				Anim.lerpColor(a.accentHover, b.accentHover, t),
				Anim.lerpColor(a.onAccent, b.onAccent, t),
				Anim.lerpColor(a.track, b.track, t),
				Anim.lerpColor(a.knob, b.knob, t),
				Anim.lerpColor(a.danger, b.danger, t),
				Anim.lerpColor(a.success, b.success, t),
				Anim.lerpColor(a.shadow, b.shadow, t)
		);
	}
}
