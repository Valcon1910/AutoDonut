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
		float k = Anim.easeInOut(knob.update(ui.dt));
		int track = Anim.lerpColor(ui.theme.track(), ui.theme.accent(), k);
		if (hover.get() > 0) track = Anim.lerpColor(track, 0xFFFFFFFF, hover.get() * 0.06f);
		ui.round(x, y, w, h, h / 2, track);
		int r = h / 2 - 2;
		int cx = x + 2 + r + Math.round(k * (w - 4 - r * 2));
		ui.circle(cx, y + h / 2, r, ui.theme.knob());
	}

	@Override
	public boolean mouseClicked(double mx, double my) {
		if (!contains(mx, my)) return false;
		setter.accept(!getter.getAsBoolean());
		return true;
	}
}
