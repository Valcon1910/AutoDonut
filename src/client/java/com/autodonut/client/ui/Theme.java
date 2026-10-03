package com.autodonut.client.ui;

import java.util.List;

import com.autodonut.client.config.AutoDonutConfig;

/**
 * Colours used by the panel. A theme is a base style (the dark or light surfaces) combined
 * with an accent colour; both are chosen on the Appearance page.
 */
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
	/** The neutral colours of a style. */
	public record Base(String name, boolean dark, int scrim, int panel, int sidebar, int surface, int surfaceHover,
			int border, int text, int textMuted, int track, int shadow) { }

	public record Accent(String name, int color) { }

	public static final List<Base> DARK_BASES = List.of(
			new Base("Midnight", true, 0x99000000, 0xFF16181D, 0xFF111317, 0xFF1F2229, 0xFF282C35, 0xFF2C3039, 0xFFE9EAEE, 0xFF8C919B, 0xFF343844, 0x66000000),
			new Base("Graphite", true, 0x99000000, 0xFF1B1B1D, 0xFF141415, 0xFF242427, 0xFF2E2E32, 0xFF333337, 0xFFEDEDED, 0xFF919196, 0xFF3A3A3F, 0x66000000),
			new Base("Abyss", true, 0x99000814, 0xFF101826, 0xFF0B111C, 0xFF172234, 0xFF1F2C42, 0xFF223049, 0xFFE6ECF5, 0xFF8494AD, 0xFF2A3A55, 0x66000000),
			new Base("Obsidian", true, 0xAA000000, 0xFF0C0C0E, 0xFF070708, 0xFF151518, 0xFF1D1D21, 0xFF232328, 0xFFF2F2F3, 0xFF85858C, 0xFF2B2B31, 0x80000000)
	);

	public static final List<Base> LIGHT_BASES = List.of(
			new Base("Daylight", false, 0x55101216, 0xFFF8F8FA, 0xFFEFF0F4, 0xFFFFFFFF, 0xFFF1F2F6, 0xFFDDDFE5, 0xFF1B1D22, 0xFF6B7080, 0xFFD5D8DF, 0x33000000),
			new Base("Paper", false, 0x55140E08, 0xFFFAF6EF, 0xFFF1EBE0, 0xFFFFFDF8, 0xFFF5EFE4, 0xFFE3DACB, 0xFF2A241C, 0xFF7A6F60, 0xFFDCD2C2, 0x33000000),
			new Base("Frost", false, 0x55081020, 0xFFF4F7FB, 0xFFE8EEF6, 0xFFFFFFFF, 0xFFEEF3F9, 0xFFD6DFEB, 0xFF162033, 0xFF63718A, 0xFFCDD7E5, 0x33000000)
	);

	public static final List<Accent> ACCENTS = List.of(
			new Accent("Donut Pink", 0xFFE8689F),
			new Accent("Ember", 0xFFF2703A),
			new Accent("Gold", 0xFFE0A526),
			new Accent("Mint", 0xFF2DBE8C),
			new Accent("Aqua", 0xFF22B4CF),
			new Accent("Ocean", 0xFF3B82F6),
			new Accent("Lavender", 0xFF9B7BFF),
			new Accent("Crimson", 0xFFE5484D)
	);

	public static Base findBase(List<Base> list, String name) {
		for (Base b : list) {
			if (b.name().equals(name)) return b;
		}
		return list.get(0);
	}

	public static Accent findAccent(String name) {
		for (Accent a : ACCENTS) {
			if (a.name().equals(name)) return a;
		}
		return ACCENTS.get(0);
	}

	public static Theme of(Base b, Accent a) {
		int accent = a.color();
		return new Theme(
				b.scrim(), b.panel(), b.sidebar(), b.surface(), b.surfaceHover(), b.border(),
				b.text(), b.textMuted(),
				accent,
				Anim.lerpColor(accent, b.dark() ? 0xFFFFFFFF : 0xFF000000, 0.12f),
				0xFFFFFFFF,
				b.track(),
				0xFFFFFFFF,
				b.dark() ? 0xFFE5484D : 0xFFD93D42,
				b.dark() ? 0xFF3DD68C : 0xFF1F9D5C,
				b.shadow()
		);
	}

	/** The theme the config currently asks for. */
	public static Theme current(AutoDonutConfig cfg) {
		Base base = cfg.darkMode ? findBase(DARK_BASES, cfg.darkStyle) : findBase(LIGHT_BASES, cfg.lightStyle);
		return of(base, findAccent(cfg.accent));
	}

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
