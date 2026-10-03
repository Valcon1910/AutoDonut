package com.autodonut.client.ui.widget;

import com.autodonut.client.ui.Anim;
import com.autodonut.client.ui.Ui;

/** Rounded button in one of a few visual styles. */
public class UiButton extends Widget {
	public enum Style { PRIMARY, SECONDARY, DANGER, GHOST }

	private final String label;
	private final Style style;
	private final Runnable action;
	private final Anim press = new Anim(0, 25);

	public UiButton(String label, Style style, Runnable action) {
		this.label = label;
		this.style = style;
		this.action = action;
		this.h = 18;
	}

	public static int widthFor(Ui ui, String label) {
		return ui.width(label) + 16;
	}

	@Override
	protected void draw(Ui ui, int mx, int my) {
		press.update(ui.dt);
		float hv = hover.get();
		int bg;
		int fg;
		int border;
		switch (style) {
			case PRIMARY -> {
				bg = Anim.lerpColor(ui.theme.accent(), ui.theme.accentHover(), hv);
				fg = ui.theme.onAccent();
				border = bg;
			}
			case DANGER -> {
				bg = Anim.lerpColor(ui.theme.surface(), ui.theme.danger(), hv);
				fg = Anim.lerpColor(ui.theme.danger(), 0xFFFFFFFF, hv);
				border = Anim.lerpColor(ui.theme.border(), ui.theme.danger(), hv);
			}
			case GHOST -> {
				bg = Anim.lerpColor(ui.theme.surface() & 0x00FFFFFF, ui.theme.surfaceHover(), hv);
				fg = Anim.lerpColor(ui.theme.textMuted(), ui.theme.text(), hv);
				border = bg;
			}
			default -> {
				bg = Anim.lerpColor(ui.theme.surface(), ui.theme.surfaceHover(), hv);
				fg = ui.theme.text();
				border = ui.theme.border();
			}
		}
		int inset = Math.round(press.get());
		ui.card(x + inset, y + inset, w - inset * 2, h - inset * 2, 3, bg, border);
		ui.textCentered(label, x + w / 2, y + (h - ui.lineHeight()) / 2 + 1, fg);
	}

	@Override
	public boolean mouseClicked(double mx, double my) {
		if (!contains(mx, my)) return false;
		press.snap(1);
		press.set(0);
		action.run();
		return true;
	}
}
