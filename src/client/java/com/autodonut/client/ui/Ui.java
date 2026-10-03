package com.autodonut.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;

/** Per-frame drawing context: shapes, text and the current theme, all faded by {@link #alpha}. */
public final class Ui {
	public GuiGraphicsExtractor g;
	public Font font;
	public Theme theme = Theme.DARK;
	public float alpha = 1f;
	public float dt;

	public int c(int argb) {
		int a = Math.round((argb >>> 24) * Anim.clamp01(alpha));
		return (a << 24) | (argb & 0x00FFFFFF);
	}

	public void fill(int x1, int y1, int x2, int y2, int color) {
		if (x2 <= x1 || y2 <= y1) return;
		int col = c(color);
		if ((col >>> 24) == 0) return;
		g.fill(x1, y1, x2, y2, col);
	}

	/** Filled rectangle with rounded corners, drawn as non-overlapping rows so translucency stays even. */
	public void round(int x, int y, int w, int h, int r, int color) {
		r = Math.min(r, Math.min(w, h) / 2);
		if (r <= 0) {
			fill(x, y, x + w, y + h, color);
			return;
		}
		fill(x, y + r, x + w, y + h - r, color);
		for (int i = 0; i < r; i++) {
			double dy = r - i - 0.5;
			int inset = (int) Math.round(r - Math.sqrt(r * r - dy * dy));
			fill(x + inset, y + i, x + w - inset, y + i + 1, color);
			fill(x + inset, y + h - i - 1, x + w - inset, y + h - i, color);
		}
	}

	/** Rounded card with a 1px border. */
	public void card(int x, int y, int w, int h, int r, int fill, int border) {
		round(x, y, w, h, r, border);
		round(x + 1, y + 1, w - 2, h - 2, Math.max(0, r - 1), fill);
	}

	public void circle(int cx, int cy, int r, int color) {
		round(cx - r, cy - r, r * 2, r * 2, r, color);
	}

	public void text(String s, int x, int y, int color) {
		if (alpha < 0.04f || s.isEmpty()) return;
		g.text(font, s, x, y, c(color), false);
	}

	public void textCentered(String s, int cx, int y, int color) {
		text(s, cx - width(s) / 2, y, color);
	}

	public void textRight(String s, int right, int y, int color) {
		text(s, right - width(s), y, color);
	}

	public int width(String s) {
		return font.width(s);
	}

	public int lineHeight() {
		return font.lineHeight;
	}

	/** Cuts the string to fit, adding an ellipsis when shortened. */
	public String trim(String s, int maxWidth) {
		if (width(s) <= maxWidth) return s;
		String dots = "...";
		int dw = width(dots);
		int end = s.length();
		while (end > 0 && width(s.substring(0, end)) + dw > maxWidth) end--;
		return s.substring(0, end) + dots;
	}

	public void item(ItemStack stack, int x, int y) {
		// Item models don't support fading, so only draw them once the panel is mostly visible.
		if (alpha > 0.6f && !stack.isEmpty()) g.fakeItem(stack, x, y);
	}

	public void scissor(int x1, int y1, int x2, int y2) {
		g.enableScissor(x1, y1, x2, y2);
	}

	public void endScissor() {
		g.disableScissor();
	}
}
