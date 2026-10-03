package com.autodonut.client.ui.widget;

import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.input.KeyEvent;
import net.minecraft.world.item.ItemStack;

import com.autodonut.client.ui.Anim;
import com.autodonut.client.ui.Ui;

/** Single-line text input with a focus ring, placeholder and blinking caret. */
public class TextField extends Widget {
	private final String placeholder;
	private final Consumer<String> onChange;
	private final Predicate<Character> allowed;
	private final int maxLength;
	private final Anim focus = new Anim(0, 18);
	private boolean focused;
	private boolean searchIcon;
	private Supplier<ItemStack> icon = () -> ItemStack.EMPTY;
	private String prefix = "";
	private String text;
	private long caretEpoch = System.currentTimeMillis();

	public TextField(String placeholder, String initial, int maxLength, Predicate<Character> allowed, Consumer<String> onChange) {
		this.placeholder = placeholder;
		this.text = initial == null ? "" : initial;
		this.maxLength = maxLength;
		this.allowed = allowed;
		this.onChange = onChange;
		this.h = 18;
	}

	public TextField searchIcon() {
		this.searchIcon = true;
		return this;
	}

	/** Item shown at the left of the field (replaces the search icon) when not empty. */
	public TextField icon(Supplier<ItemStack> icon) {
		this.icon = icon;
		return this;
	}

	public TextField prefix(String prefix) {
		this.prefix = prefix;
		return this;
	}

	public String text() {
		return text;
	}

	public void setText(String value) {
		text = value;
		onChange.accept(text);
	}

	@Override
	protected void draw(Ui ui, int mx, int my) {
		focus.set(focused ? 1 : 0);
		float f = focus.update(ui.dt);
		int border = Anim.lerpColor(Anim.lerpColor(ui.theme.border(), ui.theme.textMuted(), hover.get() * 0.4f), ui.theme.accent(), f);
		ui.card(x, y, w, h, 3, ui.theme.surface(), border);

		int tx = x + 6;
		int ty = y + (h - ui.lineHeight()) / 2 + 1;
		ItemStack stack = icon.get();
		if (!stack.isEmpty()) {
			ui.item(stack, x + 2, y + 1);
			tx = x + 21;
		} else if (searchIcon) {
			int cx = x + 9, cy = y + h / 2 - 1;
			ui.circle(cx, cy, 3, ui.theme.textMuted());
			ui.circle(cx, cy, 2, ui.theme.surface());
			ui.fill(cx + 2, cy + 2, cx + 4, cy + 4, ui.theme.textMuted());
			tx = x + 17;
		}
		if (!prefix.isEmpty()) {
			ui.text(prefix, tx, ty, ui.theme.textMuted());
			tx += ui.width(prefix);
		}

		int available = x + w - 6 - tx;
		if (text.isEmpty()) {
			ui.text(ui.trim(placeholder, available), tx, ty, Anim.lerpColor(ui.theme.textMuted(), ui.theme.surface(), 0.25f));
		}
		String shown = text;
		while (!shown.isEmpty() && ui.width(shown) > available - 3) shown = shown.substring(1);
		ui.text(shown, tx, ty, ui.theme.text());

		boolean caretOn = ((System.currentTimeMillis() - caretEpoch) / 530) % 2 == 0;
		if (focused && caretOn) {
			int cx = tx + ui.width(shown) + 1;
			ui.fill(cx, ty - 1, cx + 1, ty + ui.lineHeight() - 1, ui.theme.accent());
		}
	}

	@Override
	public boolean mouseClicked(double mx, double my) {
		boolean inside = contains(mx, my);
		setFocused(inside);
		return inside;
	}

	@Override
	public boolean isFocused() {
		return focused;
	}

	@Override
	public void setFocused(boolean value) {
		if (value && !focused) caretEpoch = System.currentTimeMillis();
		focused = value;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (!focused) return false;
		int key = event.key();
		if (key == InputConstants.KEY_BACKSPACE) {
			if (!text.isEmpty()) {
				// Ctrl/Cmd + Backspace clears the whole field.
				boolean wholeWord = (event.modifiers() & 0x2) != 0 || (event.modifiers() & 0x8) != 0;
				setText(wholeWord ? "" : text.substring(0, text.length() - 1));
			}
			caretEpoch = System.currentTimeMillis();
			return true;
		}
		if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_TAB) {
			setFocused(false);
			return true;
		}
		return false;
	}

	@Override
	public boolean charTyped(String chars) {
		if (!focused) return false;
		StringBuilder sb = new StringBuilder(text);
		for (char ch : chars.toCharArray()) {
			if (sb.length() >= maxLength) break;
			if (ch >= 32 && allowed.test(ch)) sb.append(ch);
		}
		if (!sb.toString().equals(text)) setText(sb.toString());
		caretEpoch = System.currentTimeMillis();
		return true;
	}
}
