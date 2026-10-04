package com.autodonut.client.ui.widget;

import java.util.function.Supplier;

import net.minecraft.client.input.KeyEvent;

import com.autodonut.client.ui.Anim;
import com.autodonut.client.ui.Ui;

/** A labelled settings row: title and hint on the left, a control on the right. */
public class SettingRow extends Widget {
	private final String title;
	private final String hint;
	private final Widget control;
	private final int controlWidth;
	private Supplier<String> warning = () -> null;
	private String info;

	public SettingRow(String title, String hint, Widget control, int controlWidth) {
		this.title = title;
		this.hint = hint;
		this.control = control;
		this.controlWidth = controlWidth;
	}

	/** Description shown as a tooltip when hovering the row's name. */
	public SettingRow info(String info) {
		this.info = info;
		return this;
	}

	/** Shows a warning icon after the title while the supplier returns text; hovering it shows the text. */
	public SettingRow warning(Supplier<String> warning) {
		this.warning = warning;
		return this;
	}

	@Override
	public Widget bounds(int x, int y, int w, int h) {
		super.bounds(x, y, w, h);
		control.bounds(x + w - controlWidth - 8, y + (h - control.h) / 2, controlWidth, control.h);
		return this;
	}

	@Override
	protected boolean hoverSound() {
		return false;
	}

	@Override
	protected void draw(Ui ui, int mx, int my) {
		ui.round(x, y, w, h, 3, Anim.lerpColor(ui.theme.panel(), ui.theme.surface(), 0.55f + hover.get() * 0.45f));
		int textW = w - controlWidth - 24;
		boolean twoLines = !hint.isEmpty() && h >= 21;
		int ty = twoLines ? y + h / 2 - ui.lineHeight() + 1 : y + (h - ui.lineHeight()) / 2 + 1;
		String shownTitle = ui.trim(title, textW - 14);
		ui.text(shownTitle, x + 8, ty, ui.theme.text());
		if (info != null && mx >= x && mx < x + w - controlWidth - 12 && my >= y && my < y + h) {
			ui.tooltip = info;
			ui.tooltipWarning = false;
			ui.tooltipX = mx;
			ui.tooltipY = my;
		}
		String warn = warning.get();
		if (warn != null) {
			int ix = x + 8 + ui.width(shownTitle) + 3;
			ui.warningIcon(ix, ty);
			if (mx >= ix - 2 && mx < ix + 11 && my >= ty - 2 && my < ty + 10) {
				ui.tooltip = warn;
				ui.tooltipWarning = true;
				ui.tooltipX = mx;
				ui.tooltipY = my;
			}
		}
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
