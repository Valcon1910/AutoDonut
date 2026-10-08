package com.autodonut.client.ui.widget;

import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import com.autodonut.client.ui.Anim;
import com.autodonut.client.ui.Ui;
import com.autodonut.client.ui.UiSounds;

/** Segmented control: a row of options with a sliding highlight behind the selected one. */
public class Segmented extends Widget {
	private final String[] options;
	private final IntSupplier getter;
	private final IntConsumer setter;
	private final Anim slide;
	private String[] tooltips;
	private BooleanSupplier disabled = () -> false;

	public Segmented(String[] options, IntSupplier getter, IntConsumer setter) {
		this.options = options;
		this.getter = getter;
		this.setter = setter;
		this.slide = new Anim(getter.getAsInt(), 18);
		this.h = 18;
	}

	/** Descriptions shown as a tooltip while hovering each option (null entries show none). */
	public Segmented tooltips(String... tooltips) {
		this.tooltips = tooltips;
		return this;
	}

	/** While disabled the control is shaded and can't be changed (tooltips still show). */
	public Segmented disabled(BooleanSupplier disabled) {
		this.disabled = disabled;
		return this;
	}

	@Override
	protected void draw(Ui ui, int mx, int my) {
		float base = ui.alpha;
		if (disabled.getAsBoolean()) ui.alpha = base * 0.4f;
		drawControl(ui, mx, my);
		ui.alpha = base;
	}

	private void drawControl(Ui ui, int mx, int my) {
		ui.card(x, y, w, h, 3, ui.theme.surface(), ui.theme.border());
		slide.set(getter.getAsInt());
		float s = slide.update(ui.dt);
		float segW = (w - 4) / (float) options.length;
		ui.round(x + 2 + Math.round(s * segW), y + 2, Math.round(segW), h - 4, 2, ui.theme.accent());

		int selected = getter.getAsInt();
		for (int i = 0; i < options.length; i++) {
			int sx = x + 2 + Math.round(i * segW);
			float closeness = Anim.clamp01(1f - Math.abs(s - i));
			int color = Anim.lerpColor(ui.theme.textMuted(), ui.theme.onAccent(), closeness);
			boolean over = mx >= sx && mx < sx + segW && my >= y && my < y + h;
			boolean hovered = i != selected && over;
			if (over && tooltips != null && i < tooltips.length && tooltips[i] != null) {
				ui.tooltip = tooltips[i];
				ui.tooltipWarning = false;
				ui.tooltipX = mx;
				ui.tooltipY = my;
			}
			if (hovered) color = ui.theme.text();
			ui.textCentered(ui.trim(options[i], Math.round(segW) - 4), sx + Math.round(segW / 2), y + (h - ui.lineHeight()) / 2 + 1, color);
		}
	}

	@Override
	public boolean mouseClicked(double mx, double my) {
		if (!contains(mx, my)) return false;
		if (disabled.getAsBoolean()) return true;
		float segW = (w - 4) / (float) options.length;
		int i = (int) ((mx - x - 2) / segW);
		int next = Math.max(0, Math.min(options.length - 1, i));
		if (next != getter.getAsInt()) UiSounds.click();
		setter.accept(next);
		return true;
	}
}
