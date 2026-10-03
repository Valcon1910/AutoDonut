package com.autodonut.client.ui.widget;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;

import com.autodonut.client.ui.Anim;
import com.autodonut.client.ui.Ui;
import com.autodonut.client.ui.UiSounds;

/** Horizontal slider with a value label; supports dragging and the scroll wheel. */
public class Slider extends Widget {
	private final int min;
	private final int max;
	private final IntSupplier getter;
	private final IntConsumer setter;
	private final IntFunction<String> format;
	private final Anim pos;
	private final Anim grab = new Anim(0, 20);
	private boolean dragging;
	/** Width reserved on the right for the value text. */
	private int labelWidth = 34;

	public Slider(int min, int max, IntSupplier getter, IntConsumer setter, IntFunction<String> format) {
		this.min = min;
		this.max = max;
		this.getter = getter;
		this.setter = setter;
		this.format = format;
		this.pos = new Anim(fraction(), 22);
		this.h = 14;
	}

	public Slider labelWidth(int width) {
		this.labelWidth = width;
		return this;
	}

	private float fraction() {
		return (getter.getAsInt() - min) / (float) (max - min);
	}

	private int trackX() {
		return x + 5;
	}

	private int trackW() {
		return w - labelWidth - 10;
	}

	@Override
	protected void draw(Ui ui, int mx, int my) {
		pos.set(fraction());
		float f = pos.update(ui.dt);
		grab.set(dragging ? 1 : hover.get() * 0.5f);
		grab.update(ui.dt);

		int ty = y + h / 2 - 2;
		int tx = trackX();
		int tw = trackW();
		ui.round(tx, ty, tw, 4, 2, ui.theme.track());
		int filled = Math.round(f * tw);
		ui.round(tx, ty, Math.max(4, filled), 4, 2, ui.theme.accent());

		int kx = tx + filled;
		int kh = 10 + Math.round(grab.get() * 2);
		ui.round(kx - 3, y + h / 2 - kh / 2 + 1, 6, kh, 2, 0x40000000);
		ui.round(kx - 3, y + h / 2 - kh / 2, 6, kh, 2, ui.theme.knob());
		ui.outline(kx - 3, y + h / 2 - kh / 2, 6, kh, 1, ui.theme.accent());

		ui.textRight(format.apply(getter.getAsInt()), x + w, y + (h - ui.lineHeight()) / 2 + 1, ui.theme.text());
	}

	private void setFromMouse(double mx) {
		float f = (float) ((mx - trackX()) / trackW());
		f = Anim.clamp01(f);
		setter.accept(min + Math.round(f * (max - min)));
	}

	@Override
	public boolean mouseClicked(double mx, double my) {
		if (!contains(mx, my) || mx > x + w - labelWidth) return false;
		dragging = true;
		UiSounds.click();
		setFromMouse(mx);
		return true;
	}

	@Override
	public void mouseDragged(double mx, double my) {
		if (dragging) setFromMouse(mx);
	}

	@Override
	public void mouseReleased(double mx, double my) {
		dragging = false;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double amount) {
		if (!contains(mx, my)) return false;
		int v = getter.getAsInt() + (amount > 0 ? 1 : -1);
		setter.accept(Math.max(min, Math.min(max, v)));
		return true;
	}
}
