package com.autodonut.client.ui.widget;

import net.minecraft.client.input.KeyEvent;

import com.autodonut.client.ui.Anim;
import com.autodonut.client.ui.Ui;

/** Minimal retained-mode widget with a smooth hover animation. */
public abstract class Widget {
	public int x, y, w, h;
	public boolean visible = true;
	protected final Anim hover = new Anim(0, 16);

	public Widget bounds(int x, int y, int w, int h) {
		this.x = x;
		this.y = y;
		this.w = w;
		this.h = h;
		return this;
	}

	public boolean contains(double mx, double my) {
		return visible && mx >= x && my >= y && mx < x + w && my < y + h;
	}

	public final void render(Ui ui, int mx, int my) {
		if (!visible) return;
		hover.set(contains(mx, my) ? 1 : 0);
		hover.update(ui.dt);
		draw(ui, mx, my);
	}

	protected abstract void draw(Ui ui, int mx, int my);

	public boolean mouseClicked(double mx, double my) {
		return false;
	}

	public void mouseReleased(double mx, double my) {
	}

	public void mouseDragged(double mx, double my) {
	}

	public boolean mouseScrolled(double mx, double my, double amount) {
		return false;
	}

	public boolean keyPressed(KeyEvent event) {
		return false;
	}

	public boolean charTyped(String chars) {
		return false;
	}

	public boolean isFocused() {
		return false;
	}

	public void setFocused(boolean focused) {
	}
}
