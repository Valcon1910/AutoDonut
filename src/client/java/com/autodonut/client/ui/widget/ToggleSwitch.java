package com.autodonut.client.ui.widget;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.autodonut.client.ui.Anim;
import com.autodonut.client.ui.Ui;

/** iOS-style switch whose knob slides and track colour fades. */
public class ToggleSwitch extends Widget {
	public static final int WIDTH = 28;
	public static final int HEIGHT = 14;

	private final BooleanSupplier getter;
	private final Consumer<Boolean> setter;
	private final Anim knob;

	public ToggleSwitch(BooleanSupplier getter, Consumer<Boolean> setter) {
		this.getter = getter;
		this.setter = setter;
		this.knob = new Anim(getter.getAsBoolean() ? 1 : 0, 18);
		this.w = WIDTH;
		this.h = HEIGHT;
	}

	@Override
	protected void draw(Ui ui, int mx, int my) {
		knob.set(getter.getAsBoolean() ? 1 : 0);
		paint(ui, x, y, Anim.easeInOut(knob.update(ui.dt)), hover.get());
	}

	/**
	 * Draws a switch at (x, y). {@code on} is 0..1 for the knob position, {@code hover} 0..1.
	 * Track fades to the accent, the knob has a soft drop shadow, and a small glyph inside the
	 * knob shows the state (accent dot when on, hollow ring when off).
	 */
	public static void paint(Ui ui, int x, int y, float on, float hover) {
		int w = WIDTH, h = HEIGHT;
		int track = Anim.lerpColor(ui.theme.track(), ui.theme.accent(), on);
		if (hover > 0.01f) {
			int glow = (ui.theme.accent() & 0x00FFFFFF) | (Math.round(hover * 0x55) << 24);
			ui.round(x - 2, y - 2, w + 4, h + 4, (h + 4) / 2, glow);
		}
		ui.round(x, y, w, h, h / 2, Anim.lerpColor(track, 0xFF000000, 0.18f));
		ui.round(x, y, w, h - 1, (h - 1) / 2, track);
		ui.fill(x + h / 2, y + 1, x + w - h / 2, y + 2, Anim.lerpColor(track, 0xFFFFFFFF, 0.18f));

		int r = h / 2 - 2;
		int cx = x + 2 + r + Math.round(on * (w - 4 - r * 2));
		int cy = y + h / 2;
		ui.circle(cx, cy + 1, r, 0x55000000);
		ui.circle(cx, cy, r, ui.theme.knob());
		if (on > 0.5f) {
			ui.circle(cx, cy, 2, Anim.lerpColor(ui.theme.knob(), ui.theme.accent(), (on - 0.5f) * 2));
		} else {
			int ring = Anim.lerpColor(ui.theme.track(), ui.theme.knob(), on * 2);
			ui.circle(cx, cy, 2, ring);
			ui.circle(cx, cy, 1, ui.theme.knob());
		}
	}

	@Override
	public boolean mouseClicked(double mx, double my) {
		if (!contains(mx, my)) return false;
		setter.accept(!getter.getAsBoolean());
		return true;
	}
}
