package com.autodonut.client.ui.widget;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.autodonut.client.ui.Anim;
import com.autodonut.client.ui.Ui;
import com.autodonut.client.ui.UiSounds;

/** iOS-style switch whose knob slides and track colour fades. */
public class ToggleSwitch extends Widget {
	public static final int WIDTH = 26;
	public static final int HEIGHT = 14;

	private final BooleanSupplier getter;
	private final Consumer<Boolean> setter;
	private final Anim knob;
	private BooleanSupplier disabled = () -> false;

	public ToggleSwitch(BooleanSupplier getter, Consumer<Boolean> setter) {
		this.getter = getter;
		this.setter = setter;
		this.knob = new Anim(getter.getAsBoolean() ? 1 : 0, 18);
		this.w = WIDTH;
		this.h = HEIGHT;
	}

	/** While disabled the switch is shaded and can't be flipped. */
	public ToggleSwitch disabled(BooleanSupplier disabled) {
		this.disabled = disabled;
		return this;
	}

	@Override
	protected void draw(Ui ui, int mx, int my) {
		knob.set(getter.getAsBoolean() ? 1 : 0);
		boolean off = disabled.getAsBoolean();
		float base = ui.alpha;
		if (off) ui.alpha = base * 0.4f;
		paint(ui, x, y, Anim.easeInOut(knob.update(ui.dt)), off ? 0 : hover.get());
		ui.alpha = base;
	}

	/**
	 * Draws a switch at (x, y). {@code on} is 0..1 for the knob position, {@code hover} 0..1.
	 * Pixel-crisp style: a softly rounded track and a square knob with a one-pixel shadow,
	 * so it stays sharp at any GUI scale.
	 */
	public static void paint(Ui ui, int x, int y, float on, float hover) {
		int w = WIDTH, h = HEIGHT;
		int offTrack = Anim.lerpColor(ui.theme.track(), ui.theme.textMuted(), hover * 0.2f);
		int onTrack = Anim.lerpColor(ui.theme.accent(), ui.theme.accentHover(), hover);
		int track = Anim.lerpColor(offTrack, onTrack, on);

		// Soft glow while hovered
		if (hover > 0.01f) {
			ui.round(x - 1, y - 1, w + 2, h + 2, (h + 2) / 2, (track & 0x00FFFFFF) | (Math.round(hover * 0x40) << 24));
		}
		// Pill track: darker rim, body, lighter top half for a slight curve
		ui.round(x, y, w, h, h / 2, Anim.lerpColor(track, 0xFF000000, 0.25f));
		ui.round(x + 1, y + 1, w - 2, h - 2, h / 2 - 1, track);
		ui.round(x + 2, y + 2, w - 4, (h - 4) / 2, 2, Anim.lerpColor(track, 0xFFFFFFFF, 0.10f));

		// A small ring on the right while off
		int glyphOff = (0x00FFFFFF & ui.theme.textMuted()) | (Math.round((1 - on) * 0xC0) << 24);
		int ox = x + w - 8, oy = y + h / 2 - 2;
		ui.fill(ox + 1, oy, ox + 3, oy + 1, glyphOff);
		ui.fill(ox + 1, oy + 3, ox + 3, oy + 4, glyphOff);
		ui.fill(ox, oy + 1, ox + 1, oy + 3, glyphOff);
		ui.fill(ox + 3, oy + 1, ox + 4, oy + 3, glyphOff);

		// Knob: rounded, with a drop shadow and a light top edge
		int k = h - 4;
		int kx = x + 2 + Math.round(on * (w - 4 - k));
		int ky = y + 2;
		ui.round(kx, ky + 1, k, k, k / 2, 0x50000000);
		ui.round(kx, ky, k, k, k / 2, ui.theme.knob());
		ui.fill(kx + 3, ky + 1, kx + k - 3, ky + 2, 0xFFFFFFFF);
		ui.fill(kx + 2, ky + k - 2, kx + k - 2, ky + k - 1, Anim.lerpColor(ui.theme.knob(), 0xFF000000, 0.12f));
	}

	@Override
	public boolean mouseClicked(double mx, double my) {
		if (!contains(mx, my)) return false;
		if (disabled.getAsBoolean()) return true;
		boolean next = !getter.getAsBoolean();
		setter.accept(next);
		UiSounds.toggle(next);
		return true;
	}
}
