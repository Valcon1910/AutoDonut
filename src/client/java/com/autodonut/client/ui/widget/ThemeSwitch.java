package com.autodonut.client.ui.widget;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.autodonut.client.ui.Anim;
import com.autodonut.client.ui.Ui;
import com.autodonut.client.ui.UiSounds;

/** Dark / light switch: the knob slides and morphs between a moon and a sun. */
public class ThemeSwitch extends Widget {
	private final BooleanSupplier isDark;
	private final Consumer<Boolean> setDark;
	private final Anim knob;

	public ThemeSwitch(BooleanSupplier isDark, Consumer<Boolean> setDark) {
		this.isDark = isDark;
		this.setDark = setDark;
		this.knob = new Anim(isDark.getAsBoolean() ? 0 : 1, 14);
		this.w = 34;
		this.h = 16;
	}

	@Override
	protected void draw(Ui ui, int mx, int my) {
		knob.set(isDark.getAsBoolean() ? 0 : 1);
		float k = Anim.easeInOut(knob.update(ui.dt));
		int track = Anim.lerpColor(0xFF2A2E3A, 0xFFBFD8F5, k);
		if (hover.get() > 0) track = Anim.lerpColor(track, 0xFFFFFFFF, hover.get() * 0.08f);
		ui.round(x, y, w, h, h / 2, track);

		int r = h / 2 - 2;
		int cx = x + 2 + r + Math.round(k * (w - 4 - r * 2));
		int cy = y + h / 2;
		int sun = 0xFFFFC94D;
		int moon = 0xFFE6E8F0;
		ui.circle(cx, cy, r, Anim.lerpColor(moon, sun, k));
		// The moon's crescent is a track-coloured circle that slides off as it turns into the sun.
		float bite = 1f - k;
		if (bite > 0.02f) {
			int off = Math.round(3 + (1 - bite) * 6);
			ui.circle(cx + off, cy - 2, Math.max(1, Math.round(r * 0.8f * bite + 1)), track);
		}
	}

	@Override
	public boolean mouseClicked(double mx, double my) {
		if (!contains(mx, my)) return false;
		setDark.accept(!isDark.getAsBoolean());
		UiSounds.toggle(!isDark.getAsBoolean());
		return true;
	}
}
