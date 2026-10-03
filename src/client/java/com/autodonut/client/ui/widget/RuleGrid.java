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
import com.autodonut.client.ui.UiSounds;

/**
 * Auction rules as a grid of cards. Click a card to edit it, use its switch to turn it
 * on or off. The last card adds a new rule.
 */
public class RuleGrid extends Widget {
	private static final int CARD_H = 44;
	private static final int GAP = 6;

	private final List<AuctionRule> rules;
	private final Consumer<AuctionRule> onEdit;
	private final Runnable onAdd;
	private final Runnable onChanged;
	private final Map<Object, Anim> hovers = new IdentityHashMap<>();
	private final Map<AuctionRule, Anim> knobs = new IdentityHashMap<>();
	private final Object addKey = new Object();
	private final Anim scroll = new Anim(0, 20);
	private float scrollTarget;

	public RuleGrid(List<AuctionRule> rules, Consumer<AuctionRule> onEdit, Runnable onAdd, Runnable onChanged) {
		this.rules = rules;
		this.onEdit = onEdit;
		this.onAdd = onAdd;
		this.onChanged = onChanged;
	}

	private int columns() {
		return w >= 260 ? 2 : 1;
	}

	private int cardW() {
		int cols = columns();
		return (w - (maxScroll() > 0 ? 6 : 0) - GAP * (cols - 1)) / cols;
	}

	private int cells() {
		return rules.size() + 1;
	}

	private int contentHeight() {
		int rows = (cells() + columns() - 1) / columns();
		return rows * (CARD_H + GAP) - GAP;
	}

	private int maxScroll() {
		return Math.max(0, contentHeight() - h);
	}

	private int cellX(int i) {
		return x + (i % columns()) * (cardW() + GAP);
	}

	private int cellY(int i, int off) {
		return y + (i / columns()) * (CARD_H + GAP) - off;
	}

	@Override
	protected boolean hoverSound() {
		return false;
	}

	@Override
	protected void draw(Ui ui, int mx, int my) {
		scrollTarget = Math.max(0, Math.min(maxScroll(), scrollTarget));
		scroll.set(scrollTarget);
		int off = Math.round(scroll.update(ui.dt));
		int cw = cardW();

		ui.scissor(x, y, x + w, y + h);
		for (int i = 0; i < cells(); i++) {
			int cx = cellX(i);
			int cy = cellY(i, off);
			if (cy + CARD_H < y || cy > y + h) continue;
			boolean hovered = contains(mx, my) && mx >= cx && mx < cx + cw && my >= cy && my < cy + CARD_H;
			Object key = i < rules.size() ? rules.get(i) : addKey;
			Anim hv = hovers.computeIfAbsent(key, k -> new Anim(0, 16));
			if (hovered && hv.target() < 0.5f) UiSounds.hover();
			hv.set(hovered ? 1 : 0);
			hv.update(ui.dt);
			if (i < rules.size()) drawRule(ui, rules.get(i), cx, cy, cw, hv.get());
			else drawAdd(ui, cx, cy, cw, hv.get());
		}
		ui.endScissor();

		if (maxScroll() > 0) {
			int barH = Math.max(16, h * h / contentHeight());
			int barY = y + Math.round((h - barH) * (off / (float) maxScroll()));
			ui.fill(x + w - 2, y, x + w, y + h, ui.theme.sidebar());
			ui.fill(x + w - 2, barY, x + w, barY + barH, ui.theme.track());
		}
	}

	private void drawRule(Ui ui, AuctionRule rule, int cx, int cy, int cw, float hv) {
		ItemIndex.Entry entry = ItemIndex.byId(rule.itemId);
		boolean ready = rule.isComplete();
		boolean live = ready && rule.enabled;

		int bg = Anim.lerpColor(ui.theme.surface(), ui.theme.surfaceHover(), hv);
		ui.round(cx, cy, cw, CARD_H, 3, bg);
		if (hv > 0.01f) ui.outline(cx, cy, cw, CARD_H, 1, Anim.lerpColor(bg, ui.theme.accent(), hv));

		ItemStack stack = entry == null ? ItemStack.EMPTY : entry.stack();
		ui.item(stack, cx + 6, cy + 5);

		String name = entry == null ? "No item" : entry.name();
		String tag = !ready ? (entry == null ? "Set up" : "Set price") : rule.enabled ? "Active" : "Off";
		int tagColor = !ready ? ui.theme.danger() : rule.enabled ? ui.theme.success() : ui.theme.textMuted();
		int tagW = ui.tag(tag, cx + cw - 7, cy + 9, tagColor);
		ui.text(ui.trim(name, cw - 34 - tagW - 6), cx + 26, cy + 9, ui.theme.text());

		String detail = ready
				? rule.quantityText() + "  -  $" + PriceFormat.format(rule.price()) + (rule.pricePerItem ? " each" : " per stack")
				: "Click to finish setting up";
		ui.text(ui.trim(detail, cw - 14), cx + 7, cy + 22, ui.theme.textMuted());

		// Stack-size meter: how much of a 64 stack the rule's amount covers.
		int meterW = cw - 14 - ToggleSwitch.WIDTH - 10;
		int lo = rule.mode == com.autodonut.client.config.QuantityMode.EXACTLY ? rule.amount - 1 : rule.min - 1;
		int hi = rule.mode == com.autodonut.client.config.QuantityMode.EXACTLY ? rule.amount : rule.max;
		ui.fill(cx + 7, cy + 35, cx + 7 + meterW, cy + 38, ui.theme.track());
		ui.fill(cx + 7 + Math.round(meterW * lo / 64f), cy + 35, cx + 7 + Math.max(Math.round(meterW * lo / 64f) + 2, Math.round(meterW * hi / 64f)), cy + 38,
				live ? ui.theme.accent() : ui.theme.textMuted());

		Anim knob = knobs.computeIfAbsent(rule, r -> new Anim(r.enabled ? 1 : 0, 18));
		knob.set(rule.enabled ? 1 : 0);
		float k = Anim.easeInOut(knob.update(ui.dt));
		int sx = cx + cw - ToggleSwitch.WIDTH - 6;
		int sy = cy + CARD_H - ToggleSwitch.HEIGHT - 5;
		ToggleSwitch.paint(ui, sx, sy, k, 0);
	}

	private void drawAdd(Ui ui, int cx, int cy, int cw, float hv) {
		int color = Anim.lerpColor(ui.theme.border(), ui.theme.accent(), hv);
		if (hv > 0.01f) ui.round(cx, cy, cw, CARD_H, 3, (ui.theme.surface() & 0x00FFFFFF) | (Math.round(hv * 0xAA) << 24));
		ui.dashed(cx, cy, cw, CARD_H, color);
		int textColor = Anim.lerpColor(ui.theme.textMuted(), ui.theme.accent(), hv);
		ui.textCentered("+ Add item", cx + cw / 2, cy + CARD_H / 2 - 8, textColor);
		ui.textCentered(rules.isEmpty() ? "Choose what to sell" : "Sell another item", cx + cw / 2, cy + CARD_H / 2 + 3, ui.theme.textMuted());
	}

	@Override
	public boolean mouseClicked(double mx, double my) {
		if (!contains(mx, my)) return false;
		int off = Math.round(scroll.get());
		int cw = cardW();
		for (int i = 0; i < cells(); i++) {
			int cx = cellX(i);
			int cy = cellY(i, off);
			if (mx < cx || mx >= cx + cw || my < cy || my >= cy + CARD_H) continue;
			if (i == rules.size()) {
				UiSounds.click();
				onAdd.run();
				return true;
			}
			AuctionRule rule = rules.get(i);
			int sx = cx + cw - ToggleSwitch.WIDTH - 6;
			int sy = cy + CARD_H - ToggleSwitch.HEIGHT - 5;
			if (mx >= sx - 3 && mx < sx + ToggleSwitch.WIDTH + 3 && my >= sy - 3 && my < sy + ToggleSwitch.HEIGHT + 3) {
				rule.enabled = !rule.enabled;
				UiSounds.toggle(rule.enabled);
				onChanged.run();
			} else {
				UiSounds.click();
				onEdit.accept(rule);
			}
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double amount) {
		if (!contains(mx, my) || maxScroll() == 0) return false;
		scrollTarget -= (float) amount * 20;
		return true;
	}
}
