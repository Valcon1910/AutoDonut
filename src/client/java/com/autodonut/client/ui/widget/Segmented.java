package com.autodonut.client.ui.widget;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import com.autodonut.client.ui.Anim;
import com.autodonut.client.ui.Ui;

/** Segmented control: a row of options with a sliding highlight behind the selected one. */
public class Segmented extends Widget {
	private final String[] options;
	private final IntSupplier getter;
	private final IntConsumer setter;
	private final Anim slide;

	public Segmented(String[] options, IntSupplier getter, IntConsumer setter) {
		this.options = options;
		this.getter = getter;
		this.setter = setter;
		this.slide = new Anim(getter.getAsInt(), 18);
		this.h = 18;
	}

	@Override
	protected void draw(Ui ui, int mx, int my) {
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
			boolean hovered = i != selected && mx >= sx && mx < sx + segW && my >= y && my < y + h;
			if (hovered) color = ui.theme.text();
			ui.textCentered(ui.trim(options[i], Math.round(segW) - 4), sx + Math.round(segW / 2), y + (h - ui.lineHeight()) / 2 + 1, color);
		}
	}

	@Override
	public boolean mouseClicked(double mx, double my) {
		if (!contains(mx, my)) return false;
		float segW = (w - 4) / (float) options.length;
		int i = (int) ((mx - x - 2) / segW);
		setter.accept(Math.max(0, Math.min(options.length - 1, i)));
		return true;
	}
}
