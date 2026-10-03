package com.autodonut.client.ui.widget;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.minecraft.world.item.ItemStack;

import com.autodonut.client.ui.Anim;
import com.autodonut.client.ui.Ui;

/**
 * Home-screen card for one feature: uppercase title, description, live status line and an
 * on/off switch. Enabled cards get an accent outline. Clicking the card body can open the
 * feature's own page.
 */
public class FeatureCard extends Widget {
	private final ItemStack icon;
	private final String title;
	private final String description;
	private final Supplier<String> status;
	private final BooleanSupplier getter;
	private final Consumer<Boolean> setter;
	private final Runnable onOpen;
	private final Anim on;

	public FeatureCard(ItemStack icon, String title, String description, Supplier<String> status,
			BooleanSupplier getter, Consumer<Boolean> setter, Runnable onOpen) {
		this.icon = icon;
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
		return y + 9;
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

		// Item icon on a soft tile
		ui.round(x + 6, y + 5, 20, 20, 3, Anim.lerpColor(ui.theme.panel(), ui.theme.accent(), k * 0.18f));
		ui.item(icon, x + 8, y + 7);

		int textW = w - 32 - ToggleSwitch.WIDTH - 14;
		ui.text(ui.trim(title, textW), x + 32, y + 11, Anim.lerpColor(ui.theme.text(), ui.theme.accent(), k));
		java.util.List<String> lines = ui.wrap(description, w - 16);
		for (int i = 0; i < Math.min(2, lines.size()); i++) {
			ui.text(lines.get(i), x + 8, y + 31 + i * 10, ui.theme.textMuted());
		}

		String tag = k > 0.5f ? "On" : "Off";
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
