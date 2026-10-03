package com.autodonut.client.ui.widget;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import com.autodonut.client.ui.Anim;
import com.autodonut.client.ui.Ui;

/**
 * A grid of selectable tiles (used for colour styles and accents). The selected tile gets
 * an accent outline; hovering lifts the tile colour slightly.
 */
public class ChoiceGrid extends Widget {
	/** Draws the contents of one tile. */
	public interface TilePainter {
		void paint(Ui ui, int index, int x, int y, int w, int h, boolean selected);
	}

	private final int count;
	private final int columns;
	private final int tileH;
	private final IntSupplier selected;
	private final IntConsumer onSelect;
	private final TilePainter painter;
	private final List<Anim> hovers = new ArrayList<>();
	private static final int GAP = 6;

	public ChoiceGrid(int count, int columns, int tileH, IntSupplier selected, IntConsumer onSelect, TilePainter painter) {
		this.count = count;
		this.columns = columns;
		this.tileH = tileH;
		this.selected = selected;
		this.onSelect = onSelect;
		this.painter = painter;
		for (int i = 0; i < count; i++) hovers.add(new Anim(0, 16));
		int rows = (count + columns - 1) / columns;
		this.h = rows * tileH + (rows - 1) * GAP;
	}

	private int tileW() {
		return (w - GAP * (columns - 1)) / columns;
	}

	private int tileX(int i) {
		return x + (i % columns) * (tileW() + GAP);
	}

	private int tileY(int i) {
		return y + (i / columns) * (tileH + GAP);
	}

	@Override
	protected void draw(Ui ui, int mx, int my) {
		int tw = tileW();
		int sel = selected.getAsInt();
		for (int i = 0; i < count; i++) {
			int tx = tileX(i), ty = tileY(i);
			boolean over = mx >= tx && mx < tx + tw && my >= ty && my < ty + tileH;
			Anim hv = hovers.get(i);
			hv.set(over ? 1 : 0);
			hv.update(ui.dt);
			int bg = Anim.lerpColor(ui.theme.surface(), ui.theme.surfaceHover(), hv.get());
			ui.round(tx, ty, tw, tileH, 3, bg);
			if (i == sel) ui.outline(tx, ty, tw, tileH, 1, ui.theme.accent());
			else if (hv.get() > 0.01f) ui.outline(tx, ty, tw, tileH, 1, Anim.lerpColor(bg, ui.theme.border(), hv.get()));
			painter.paint(ui, i, tx, ty, tw, tileH, i == sel);
		}
	}

	@Override
	public boolean mouseClicked(double mx, double my) {
		if (!contains(mx, my)) return false;
		int tw = tileW();
		for (int i = 0; i < count; i++) {
			int tx = tileX(i), ty = tileY(i);
			if (mx >= tx && mx < tx + tw && my >= ty && my < ty + tileH) {
				onSelect.accept(i);
				return true;
			}
		}
		return false;
	}
}
