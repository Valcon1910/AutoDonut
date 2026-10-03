package com.autodonut.client.ui.widget;

import net.minecraft.client.input.KeyEvent;

import com.autodonut.client.ui.Anim;
import com.autodonut.client.ui.Ui;

/** A labelled settings row: title and hint on the left, a control on the right. */
public class SettingRow extends Widget {
	private final String title;
	private final String hint;
	private final Widget control;
	private final int controlWidth;

	public SettingRow(String title, String hint, Widget control, int controlWidth) {
		this.title = title;
		this.hint = hint;
		this.control = control;
		this.controlWidth = controlWidth;
	}

	@Override
	public Widget bounds(int x, int y, int w, int h) {
		super.bounds(x, y, w, h);
		control.bounds(x + w - controlWidth - 8, y + (h - control.h) / 2, controlWidth, control.h);
		return this;
	}

	@Override
	protected void draw(Ui ui, int mx, int my) {
		ui.round(x, y, w, h, 3, Anim.lerpColor(ui.theme.panel(), ui.theme.surface(), 0.55f + hover.get() * 0.45f));
		int textW = w - controlWidth - 24;
		boolean twoLines = !hint.isEmpty() && h >= 22;
		int ty = twoLines ? y + h / 2 - ui.lineHeight() + 1 : y + (h - ui.lineHeight()) / 2 + 1;
		ui.text(ui.trim(title, textW), x + 8, ty, ui.theme.text());
		if (twoLines) {
			ui.text(ui.trim(hint, textW), x + 8, ty + ui.lineHeight() + 1, ui.theme.textMuted());
		}
		control.render(ui, mx, my);
	}

	@Override
	public boolean mouseClicked(double mx, double my) {
		return control.mouseClicked(mx, my);
	}

	@Override
	public void mouseReleased(double mx, double my) {
		control.mouseReleased(mx, my);
	}

	@Override
	public void mouseDragged(double mx, double my) {
		control.mouseDragged(mx, my);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double amount) {
		return control.mouseScrolled(mx, my, amount);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		return control.keyPressed(event);
	}

	@Override
	public boolean charTyped(String chars) {
		return control.charTyped(chars);
	}
}
