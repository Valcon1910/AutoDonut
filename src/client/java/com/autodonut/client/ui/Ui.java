package com.autodonut.client.ui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/** Per-frame drawing context: shapes, text and the current theme, all faded by {@link #alpha}. */
public final class Ui {
	private static final Identifier LOGO = Identifier.fromNamespaceAndPath("autodonut", "logo.png");

	public GuiGraphicsExtractor g;
	public Font font;
	public Theme theme = Theme.of(Theme.DARK_BASES.get(0), Theme.ACCENTS.get(0));
	public float alpha = 1f;
	public float dt;
	/** Tooltip requested this frame; drawn on top by the screen, then cleared. */
	public String tooltip;
	public int tooltipX, tooltipY;
	/** Warning tooltips get an amber edge; plain descriptions use the accent colour. */
	public boolean tooltipWarning = true;

	public static final int WARNING = 0xFFF5A524;

	/** Amber warning triangle with an exclamation mark, 9x8 pixels. */
	public void warningIcon(int x, int y) {
		for (int i = 0; i < 8; i++) {
			int half = i / 2 + (i > 0 ? 1 : 0);
			fill(x + 4 - half, y + i, x + 5 + half, y + i + 1, WARNING);
		}
		fill(x + 4, y + 2, x + 5, y + 5, 0xFF1A1A1A);
		fill(x + 4, y + 6, x + 5, y + 7, 0xFF1A1A1A);
	}

	/** Draws the pending tooltip inside the given bounds. */
	public void drawTooltip(int minX, int minY, int maxX, int maxY) {
		if (tooltip == null) return;
		java.util.List<String> lines = wrap(tooltip, 170);
		int w = 0;
		for (String l : lines) w = Math.max(w, width(l));
		w += 14;
		int h = lines.size() * 10 + 8;
		int x = Math.min(tooltipX + 8, maxX - w);
		int y = tooltipY + 10;
		if (y + h > maxY) y = tooltipY - h - 4;
		x = Math.max(minX, x);
		y = Math.max(minY, y);
		round(x + 1, y + 2, w, h, 3, 0x55000000);
		int edge = tooltipWarning ? WARNING : theme.accent();
		card(x, y, w, h, 3, theme.surface(), edge);
		fill(x + 1, y + 2, x + 3, y + h - 2, edge);
		for (int i = 0; i < lines.size(); i++) text(lines.get(i), x + 8, y + 5 + i * 10, theme.text());
		tooltip = null;
	}

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

	/** Thin horizontal progress bar like a stat meter. */
	public void meter(int x, int y, int w, float fraction, int fillColor) {
		fill(x, y, x + w, y + 3, theme.track());
		int fw = Math.round(Anim.clamp01(fraction) * w);
		if (fw > 0) fill(x, y, x + fw, y + 3, fillColor);
	}

	/** Small uppercase status label, e.g. "RUNNING". Returns its width. */
	public int tag(String label, int right, int y, int color) {
		textRight(label, right, y, color);
		return width(label);
	}

	/** 1px dashed rounded-ish outline. */
	public void dashed(int x, int y, int w, int h, int color) {
		for (int i = x + 3; i < x + w - 3; i += 6) {
			fill(i, y, Math.min(i + 3, x + w - 3), y + 1, color);
			fill(i, y + h - 1, Math.min(i + 3, x + w - 3), y + h, color);
		}
		for (int j = y + 3; j < y + h - 3; j += 6) {
			fill(x, j, x + 1, Math.min(j + 3, y + h - 3), color);
			fill(x + w - 1, j, x + w, Math.min(j + 3, y + h - 3), color);
		}
	}

	/** Rectangle outline of the given thickness with slightly rounded corners. */
	public void outline(int x, int y, int w, int h, int t, int color) {
		fill(x + 2, y, x + w - 2, y + t, color);
		fill(x + 2, y + h - t, x + w - 2, y + h, color);
		fill(x, y + 2, x + t, y + h - 2, color);
		fill(x + w - t, y + 2, x + w, y + h - 2, color);
		fill(x + 1, y + 1, x + 2, y + 2, color);
		fill(x + w - 2, y + 1, x + w - 1, y + 2, color);
		fill(x + 1, y + h - 2, x + 2, y + h - 1, color);
		fill(x + w - 2, y + h - 2, x + w - 1, y + h - 1, color);
	}

	public void circle(int cx, int cy, int r, int color) {
		round(cx - r, cy - r, r * 2, r * 2, r, color);
	}

	public void text(String s, int x, int y, int color) {
		if (alpha < 0.04f || s.isEmpty()) return;
		g.text(font, s, x, y, c(color), false);
	}

	public void bold(String s, int x, int y, int color) {
		if (alpha < 0.04f || s.isEmpty()) return;
		g.text(font, Component.literal(s).withStyle(ChatFormatting.BOLD), x, y, c(color));
	}

	public int boldWidth(String s) {
		return font.width(Component.literal(s).withStyle(ChatFormatting.BOLD));
	}

	/** The mod icon, drawn from assets/autodonut/icon.png. */
	public void logo(int x, int y, int size) {
		if (alpha > 0.6f) g.blit(RenderPipelines.GUI_TEXTURED, LOGO, x, y, 0, 0, size, size, 128, 128, 128, 128);
	}

	/** Draws a vanilla item texture (textures/item/<name>.png) at 16x16; works even before items are loaded. */
	public void itemTexture(String name, int x, int y) {
		if (alpha < 0.6f) return;
		g.blit(RenderPipelines.GUI_TEXTURED, Identifier.withDefaultNamespace("textures/item/" + name + ".png"), x, y, 0, 0, 16, 16, 16, 16, 16, 16);
	}

	/**
	 * Icon for a search entry: the real item when item data is loaded, otherwise drawn from the
	 * item's model textures (the title screen has no item data yet), see {@link OfflineIcons}.
	 */
	public void entryIcon(com.autodonut.client.auction.ItemIndex.Entry e, int x, int y) {
		if (!e.stack().isEmpty()) {
			item(e.stack(), x, y);
			return;
		}
		if (e.id().startsWith("#") || alpha < 0.6f) return;
		OfflineIcons.draw(g, e.id(), x, y);
	}

	/** Greedy word wrap. */
	public List<String> wrap(String text, int maxWidth) {
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ")) {
			String candidate = line.isEmpty() ? word : line + " " + word;
			if (width(candidate) > maxWidth && !line.isEmpty()) {
				lines.add(line.toString());
				line = new StringBuilder(word);
			} else {
				line = new StringBuilder(candidate);
			}
		}
		if (!line.isEmpty()) lines.add(line.toString());
		return lines;
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
