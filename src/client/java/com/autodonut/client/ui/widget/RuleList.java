package com.autodonut.client.ui.widget;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import net.minecraft.world.item.ItemStack;

import com.autodonut.client.auction.ItemIndex;
import com.autodonut.client.config.AuctionRule;
import com.autodonut.client.config.PriceFormat;
import com.autodonut.client.ui.Anim;
import com.autodonut.client.ui.Ui;

/** Scrollable list of auction rules. Click a row to edit; each row has its own switch. */
public class RuleList extends Widget {
	private static final int ROW_H = 28;
	private static final int GAP = 4;

	private final List<AuctionRule> rules;
	private final Consumer<AuctionRule> onEdit;
	private final Runnable onChanged;
	private final Map<AuctionRule, Anim> knobs = new IdentityHashMap<>();
	private final Map<AuctionRule, Anim> hovers = new IdentityHashMap<>();
	private final Anim scroll = new Anim(0, 20);
	private float scrollTarget;

	public RuleList(List<AuctionRule> rules, Consumer<AuctionRule> onEdit, Runnable onChanged) {
		this.rules = rules;
		this.onEdit = onEdit;
		this.onChanged = onChanged;
	}

	private int contentHeight() {
		return rules.size() * (ROW_H + GAP) - GAP;
	}

	private int maxScroll() {
		return Math.max(0, contentHeight() - h);
	}

	@Override
	protected void draw(Ui ui, int mx, int my) {
		scrollTarget = Math.max(0, Math.min(maxScroll(), scrollTarget));
		scroll.set(scrollTarget);
		int off = Math.round(scroll.update(ui.dt));

		if (rules.isEmpty()) {
			int cy = y + h / 2 - 8;
			ui.textCentered("No items yet", x + w / 2, cy, ui.theme.text());
			ui.textCentered("Click \"+ Add item\" to choose what to sell", x + w / 2, cy + 12, ui.theme.textMuted());
			return;
		}

		ui.scissor(x, y, x + w, y + h);
		for (int i = 0; i < rules.size(); i++) {
			AuctionRule rule = rules.get(i);
			int ry = y + i * (ROW_H + GAP) - off;
			if (ry + ROW_H < y || ry > y + h) continue;
			drawRow(ui, rule, x, ry, w - (maxScroll() > 0 ? 6 : 0), mx, my);
		}
		ui.endScissor();

		if (maxScroll() > 0) {
			int barH = Math.max(16, h * h / contentHeight());
			int barY = y + Math.round((h - barH) * (off / (float) maxScroll()));
			ui.round(x + w - 3, barY, 3, barH, 1, ui.theme.track());
		}
	}

	private void drawRow(Ui ui, AuctionRule rule, int rx, int ry, int rw, int mx, int my) {
		boolean rowHover = contains(mx, my) && mx >= rx && mx < rx + rw && my >= ry && my < ry + ROW_H;
		Anim hv = hovers.computeIfAbsent(rule, r -> new Anim(0, 16));
		hv.set(rowHover ? 1 : 0);
		hv.update(ui.dt);

		int bg = Anim.lerpColor(ui.theme.surface(), ui.theme.surfaceHover(), hv.get());
		int border = Anim.lerpColor(ui.theme.border(), ui.theme.accent(), hv.get() * 0.5f);
		ui.card(rx, ry, rw, ROW_H, 6, bg, border);

		ItemIndex.Entry entry = ItemIndex.byId(rule.itemId);
		ItemStack stack = entry == null ? ItemStack.EMPTY : entry.stack();
		ui.round(rx + 5, ry + 5, 18, 18, 4, ui.theme.panel());
		ui.item(stack, rx + 6, ry + 6);

		String name = entry == null ? "Choose an item" : entry.name();
		String summary;
		if (!rule.isComplete()) {
			summary = entry == null ? "Not set up yet" : "Set a price";
		} else {
			summary = rule.mode.label() + " " + rule.amount + "  -  " + PriceFormat.format(rule.price())
					+ (rule.pricePerItem ? " each" : " per stack");
		}
		int textW = rw - 28 - ToggleSwitch.WIDTH - 32;
		ui.text(ui.trim(name, textW), rx + 28, ry + 5, ui.theme.text());
		ui.text(ui.trim(summary, textW), rx + 28, ry + 16, rule.isComplete() ? ui.theme.textMuted() : ui.theme.danger());

		// Enable switch
		Anim knob = knobs.computeIfAbsent(rule, r -> new Anim(r.enabled ? 1 : 0, 18));
		knob.set(rule.enabled ? 1 : 0);
		float k = Anim.easeInOut(knob.update(ui.dt));
		int sx = rx + rw - ToggleSwitch.WIDTH - 22;
		int sy = ry + (ROW_H - ToggleSwitch.HEIGHT) / 2;
		ui.round(sx, sy, ToggleSwitch.WIDTH, ToggleSwitch.HEIGHT, 7, Anim.lerpColor(ui.theme.track(), ui.theme.accent(), k));
		int r = ToggleSwitch.HEIGHT / 2 - 2;
		ui.circle(sx + 2 + r + Math.round(k * (ToggleSwitch.WIDTH - 4 - r * 2)), sy + ToggleSwitch.HEIGHT / 2, r, ui.theme.knob());

		// Chevron hint that the row opens the editor
		ui.text(">", rx + rw - 12, ry + (ROW_H - ui.lineHeight()) / 2 + 1, Anim.lerpColor(ui.theme.textMuted(), ui.theme.accent(), hv.get()));
	}

	@Override
	public boolean mouseClicked(double mx, double my) {
		if (!contains(mx, my) || rules.isEmpty()) return false;
		int off = Math.round(scroll.get());
		int rw = w - (maxScroll() > 0 ? 6 : 0);
		for (int i = 0; i < rules.size(); i++) {
			int ry = y + i * (ROW_H + GAP) - off;
			if (my < ry || my >= ry + ROW_H || mx >= x + rw) continue;
			AuctionRule rule = rules.get(i);
			int sx = x + rw - ToggleSwitch.WIDTH - 22;
			if (mx >= sx - 3 && mx < sx + ToggleSwitch.WIDTH + 3) {
				rule.enabled = !rule.enabled;
				onChanged.run();
			} else {
				onEdit.accept(rule);
			}
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double amount) {
		if (!contains(mx, my) || maxScroll() == 0) return false;
		scrollTarget -= (float) amount * 18;
		return true;
	}
}
