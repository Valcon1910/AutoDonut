package com.autodonut.client.ui.widget;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.autodonut.client.ui.Anim;
import com.autodonut.client.ui.Ui;

/** iOS-style switch whose knob slides and track colour fades. */
public class ToggleSwitch extends Widget {
	public static final int WIDTH = 26;
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
	 * Pixel-crisp style: a softly rounded track and a square knob with a one-pixel shadow,
	 * so it stays sharp at any GUI scale.
	 */
	public static void paint(Ui ui, int x, int y, float on, float hover) {
		int w = WIDTH, h = HEIGHT;
		int offTrack = Anim.lerpColor(ui.theme.track(), ui.theme.textMuted(), hover * 0.25f);
		int onTrack = Anim.lerpColor(ui.theme.accent(), ui.theme.accentHover(), hover);
		int track = Anim.lerpColor(offTrack, onTrack, on);
		ui.round(x, y, w, h, 3, track);

		int k = h - 4;
		int kx = x + 2 + Math.round(on * (w - 4 - k));
		int ky = y + 2;
		ui.round(kx, ky + 1, k, k, 2, 0x40000000);
		ui.round(kx, ky, k, k, 2, Anim.lerpColor(Anim.lerpColor(ui.theme.knob(), ui.theme.textMuted(), 0.15f), ui.theme.knob(), on));
		// Two grip lines on the knob
		int grip = Anim.lerpColor(ui.theme.track(), ui.theme.accent(), on);
		ui.fill(kx + k / 2 - 2, ky + 3, kx + k / 2 - 1, ky + k - 3, grip);
		ui.fill(kx + k / 2 + 1, ky + 3, kx + k / 2 + 2, ky + k - 3, grip);
	}

	@Override
	public boolean mouseClicked(double mx, double my) {
		if (!contains(mx, my)) return false;
		setter.accept(!getter.getAsBoolean());
		return true;
	}
}
