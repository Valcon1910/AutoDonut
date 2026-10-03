package com.autodonut.client.ui.widget;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import com.autodonut.client.ui.Anim;
import com.autodonut.client.ui.Ui;
import com.autodonut.client.ui.UiSounds;

/** Slider with a minimum and a maximum handle. Drag either handle or scroll near it. */
public class RangeSlider extends Widget {
	private final int lo;
	private final int hi;
	private final IntSupplier minGet;
	private final IntConsumer minSet;
	private final IntSupplier maxGet;
	private final IntConsumer maxSet;
	private final Anim minPos;
	private final Anim maxPos;
	/** 0 = none, 1 = min handle, 2 = max handle. */
	private int dragging;

	public RangeSlider(int lo, int hi, IntSupplier minGet, IntConsumer minSet, IntSupplier maxGet, IntConsumer maxSet) {
		this.lo = lo;
		this.hi = hi;
		this.minGet = minGet;
		this.minSet = minSet;
		this.maxGet = maxGet;
		this.maxSet = maxSet;
		this.minPos = new Anim(frac(minGet.getAsInt()), 22);
		this.maxPos = new Anim(frac(maxGet.getAsInt()), 22);
		this.h = 26;
	}

	private float frac(int v) {
		return (v - lo) / (float) (hi - lo);
	}

	private int trackX() {
		return x + 4;
	}

	private int trackW() {
		return w - 8;
	}

	private int valueAt(double mx) {
		float f = Anim.clamp01((float) ((mx - trackX()) / trackW()));
		return lo + Math.round(f * (hi - lo));
	}

	@Override
	protected void draw(Ui ui, int mx, int my) {
		minPos.set(frac(minGet.getAsInt()));
		maxPos.set(frac(maxGet.getAsInt()));
		float a = minPos.update(ui.dt);
		float b = maxPos.update(ui.dt);
		int tx = trackX(), tw = trackW();
		int ty = y + 6;
		ui.round(tx, ty, tw, 4, 2, ui.theme.track());
		int ax = tx + Math.round(a * tw);
		int bx = tx + Math.round(b * tw);
		ui.fill(ax, ty, Math.max(ax + 1, bx), ty + 4, ui.theme.accent());

		for (int i = 0; i < 2; i++) {
			int kx = i == 0 ? ax : bx;
			boolean active = dragging == i + 1;
			int kh = active ? 12 : 10;
			int ky = ty + 2 - kh / 2;
			ui.round(kx - 3, ky + 1, 6, kh, 2, 0x40000000);
			ui.round(kx - 3, ky, 6, kh, 2, ui.theme.knob());
			ui.outline(kx - 3, ky, 6, kh, 1, ui.theme.accent());
		}

		String lowText = "Minimum " + minGet.getAsInt();
		String highText = "Maximum " + maxGet.getAsInt();
		ui.text(lowText, x, y + 16, ui.theme.textMuted());
		ui.textRight(highText, x + w, y + 16, ui.theme.textMuted());
	}

	private void apply(double mx) {
		int v = valueAt(mx);
		if (dragging == 1) minSet.accept(Math.min(v, maxGet.getAsInt()));
		else if (dragging == 2) maxSet.accept(Math.max(v, minGet.getAsInt()));
	}

	@Override
	public boolean mouseClicked(double mx, double my) {
		if (!contains(mx, my) || my > y + 14) return false;
		int v = valueAt(mx);
		int dMin = Math.abs(v - minGet.getAsInt());
		int dMax = Math.abs(v - maxGet.getAsInt());
		UiSounds.click();
		dragging = dMin < dMax || (dMin == dMax && v < minGet.getAsInt()) ? 1 : 2;
		apply(mx);
		return true;
	}

	@Override
	public void mouseDragged(double mx, double my) {
		if (dragging != 0) apply(mx);
	}

	@Override
	public void mouseReleased(double mx, double my) {
		dragging = 0;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double amount) {
		if (!contains(mx, my)) return false;
		int step = amount > 0 ? 1 : -1;
		int v = valueAt(mx);
		boolean nearMin = Math.abs(v - minGet.getAsInt()) <= Math.abs(v - maxGet.getAsInt());
		if (nearMin) minSet.accept(Math.max(lo, Math.min(maxGet.getAsInt(), minGet.getAsInt() + step)));
		else maxSet.accept(Math.min(hi, Math.max(minGet.getAsInt(), maxGet.getAsInt() + step)));
		return true;
	}
}
