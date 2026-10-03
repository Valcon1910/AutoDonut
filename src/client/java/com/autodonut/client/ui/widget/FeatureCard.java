package com.autodonut.client.ui.widget;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.autodonut.client.ui.Anim;
import com.autodonut.client.ui.Ui;

/**
 * Home-screen card for one feature: uppercase title, description, live status line and an
 * on/off switch. Enabled cards get an accent outline. Clicking the card body can open the
 * feature's own page.
 */
public class FeatureCard extends Widget {
	private final String title;
	private final String description;
	private final Supplier<String> status;
	private final BooleanSupplier getter;
	private final Consumer<Boolean> setter;
	private final Runnable onOpen;
	private final Anim on;

	public FeatureCard(String title, String description, Supplier<String> status,
			BooleanSupplier getter, Consumer<Boolean> setter, Runnable onOpen) {
		this.title = title;
		this.description = description;
		this.status = status;
		this.getter = getter;
		this.setter = setter;
		this.onOpen = onOpen;
		this.on = new Anim(getter.getAsBoolean() ? 1 : 0, 14);
	}

	private int switchX() {
		return x + w - ToggleSwitch.WIDTH - 8;
	}

	private int switchY() {
		return y + 7;
	}

	@Override
	protected void draw(Ui ui, int mx, int my) {
		on.set(getter.getAsBoolean() ? 1 : 0);
		float k = Anim.easeInOut(on.update(ui.dt));
		float hv = hover.get();

		int bg = Anim.lerpColor(ui.theme.surface(), ui.theme.surfaceHover(), hv);
		bg = Anim.lerpColor(bg, ui.theme.accent(), k * 0.06f);
		ui.round(x, y, w, h, 3, bg);
		float outline = Math.max(k, hv * 0.6f);
		if (outline > 0.01f) ui.outline(x, y, w, h, 1, Anim.lerpColor(bg, ui.theme.accent(), outline));

		// Icon chip
		ui.round(x + 7, y + 7, 10, 10, 2, Anim.lerpColor(ui.theme.track(), ui.theme.accent(), k));
		ui.fill(x + 10, y + 10, x + 14, y + 14, Anim.lerpColor(ui.theme.surface(), ui.theme.onAccent(), k));

		int textW = w - 22 - ToggleSwitch.WIDTH - 14;
		ui.text(ui.trim(title.toUpperCase(java.util.Locale.ROOT), textW), x + 22, y + 9, Anim.lerpColor(ui.theme.text(), ui.theme.accent(), k));
		java.util.List<String> lines = ui.wrap(description, w - 16);
		for (int i = 0; i < Math.min(2, lines.size()); i++) {
			ui.text(lines.get(i), x + 8, y + 24 + i * 10, ui.theme.textMuted());
		}

		String tag = k > 0.5f ? "ON" : "OFF";
		int tagColor = k > 0.5f ? ui.theme.success() : ui.theme.textMuted();
		ui.fill(x + 8, y + h - 19, x + w - 8, y + h - 18, ui.theme.border());
		ui.text(tag, x + 8, y + h - 13, tagColor);
		String st = status.get();
		if (!st.isEmpty()) ui.text(ui.trim(st, w - 30 - (onOpen != null ? 16 : 0)), x + 8 + ui.width(tag) + 6, y + h - 13, ui.theme.textMuted());
		if (onOpen != null) ui.textRight(">", x + w - 8, y + h - 13, Anim.lerpColor(ui.theme.textMuted(), ui.theme.accent(), hv));

		int sx = switchX(), sy = switchY();
		ToggleSwitch.paint(ui, sx, sy, k, 0);
	}

	@Override
	public boolean mouseClicked(double mx, double my) {
		if (!contains(mx, my)) return false;
		int sx = switchX(), sy = switchY();
		boolean onSwitch = mx >= sx - 3 && mx < sx + ToggleSwitch.WIDTH + 3 && my >= sy - 3 && my < sy + ToggleSwitch.HEIGHT + 3;
		if (onSwitch || onOpen == null) setter.accept(!getter.getAsBoolean());
		else onOpen.run();
		return true;
	}
}
