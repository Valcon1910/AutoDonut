package com.autodonut.client.ui.widget;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import net.minecraft.world.item.ItemStack;

import com.autodonut.client.auction.ItemIndex;
import com.autodonut.client.config.AuctionRule;
import com.autodonut.client.config.BuyRule;
import com.autodonut.client.config.PriceFormat;
import com.autodonut.client.ui.Anim;
import com.autodonut.client.ui.Ui;
import com.autodonut.client.ui.UiSounds;

/**
 * Rules as a grid of cards. Click a card to edit it, use its switch to turn it on or off.
 * The last card adds a new rule. An {@link Adapter} describes each kind of rule.
 */
public class RuleGrid<R> extends Widget {
	/** What a card shows for one kind of rule. */
	public interface Adapter<R> {
		List<String> items(R rule);

		boolean enabled(R rule);

		void setEnabled(R rule, boolean on);

		boolean ready(R rule);

		/** Tag shown while not ready but an item is chosen, e.g. "Set price". */
		String unfinishedTag();

		String detail(R rule);

		/** Meter range as fractions {from, to} of the bar. */
		float[] meter(R rule);

		/** Second line of the add card: for an empty grid, and otherwise. */
		String addHint(boolean empty);
	}

	/** Auto Auction items: quantity and price, with a stack-size meter. */
	public static final Adapter<AuctionRule> AUCTION = new Adapter<>() {
		public List<String> items(AuctionRule r) {
			return r.items;
		}

		public boolean enabled(AuctionRule r) {
			return r.enabled;
		}

		public void setEnabled(AuctionRule r, boolean on) {
			r.enabled = on;
		}

		public boolean ready(AuctionRule r) {
			return r.isComplete();
		}

		public String unfinishedTag() {
			return "Set price";
		}

		public String detail(AuctionRule r) {
			return r.quantityText() + "  -  $" + PriceFormat.format(r.price()) + (r.pricePerItem ? " each" : " per stack");
		}

		public float[] meter(AuctionRule r) {
			boolean exact = r.mode == com.autodonut.client.config.QuantityMode.EXACTLY;
			return new float[]{(exact ? r.amount - 1 : r.min - 1) / 64f, (exact ? r.amount : r.max) / 64f};
		}

		public String addHint(boolean empty) {
			return empty ? "Choose what to sell" : "Sell another item";
		}
	};

	/** Auto Buy items: budget and speed, with a purchase-limit meter. */
	public static final Adapter<BuyRule> BUY = new Adapter<>() {
		public List<String> items(BuyRule r) {
			return r.items;
		}

		public boolean enabled(BuyRule r) {
			return r.enabled;
		}

		public void setEnabled(BuyRule r, boolean on) {
			r.enabled = on;
		}

		public boolean ready(BuyRule r) {
			return r.isComplete();
		}

		public String unfinishedTag() {
			return "Set budget";
		}

		public String detail(BuyRule r) {
			String limit = r.pauseAfterEnabled ? "  -  " + r.purchases + "/" + r.pauseAfterCount + " bought" : "";
			String qty = r.quantityText().isEmpty() ? "" : "  -  " + r.quantityText();
			return r.budgetText() + qty + "  -  " + r.speed.label() + limit;
		}

		public float[] meter(BuyRule r) {
			// Purchase limit progress, or the speed level when there's no limit.
			if (r.pauseAfterEnabled) return new float[]{0, Math.min(1f, r.purchases / (float) r.pauseAfterCount)};
			return new float[]{0, (r.speed.ordinal() + 1) / (float) BuyRule.Speed.values().length};
		}

		public String addHint(boolean empty) {
			return empty ? "Choose what to buy" : "Buy another item";
		}
	};

	private static final int CARD_H = 44;
	private static final int GAP = 6;

	private final List<R> rules;
	private final Consumer<R> onEdit;
	private final Runnable onAdd;
	private final Consumer<R> onChanged;
	private final Map<Object, Anim> hovers = new IdentityHashMap<>();
	private final Map<R, Anim> knobs = new IdentityHashMap<>();
	private final Object addKey = new Object();
	private java.util.function.BooleanSupplier locked = () -> false;
	private final Anim scroll = new Anim(0, 20);
	private float scrollTarget;

	private final Adapter<R> adapter;

	public RuleGrid(List<R> rules, Adapter<R> adapter, Consumer<R> onEdit, Runnable onAdd, Consumer<R> onChanged) {
		this.rules = rules;
		this.adapter = adapter;
		this.onEdit = onEdit;
		this.onAdd = onAdd;
		this.onChanged = onChanged;
	}

	/** While locked (offline) item switches are shaded, show off and can't be flipped. */
	public RuleGrid<R> locked(java.util.function.BooleanSupplier locked) {
		this.locked = locked;
		return this;
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

	private void drawRule(Ui ui, R rule, int cx, int cy, int cw, float hv) {
		List<String> items = adapter.items(rule);
		boolean enabled = adapter.enabled(rule);
		ItemIndex.Entry entry = items.isEmpty() ? null : ItemIndex.entryFor(items.get(0));
		boolean ready = adapter.ready(rule);
		boolean live = ready && enabled && !locked.getAsBoolean();

		int bg = Anim.lerpColor(ui.theme.surface(), ui.theme.surfaceHover(), hv);
		ui.round(cx, cy, cw, CARD_H, 3, bg);
		if (hv > 0.01f) ui.outline(cx, cy, cw, CARD_H, 1, Anim.lerpColor(bg, ui.theme.accent(), hv));

		if (entry != null) ui.entryIcon(entry, cx + 6, cy + 5);

		String name = entry == null ? "No item" : entry.name() + (items.size() > 1 ? " +" + (items.size() - 1) : "");
		boolean offline = locked.getAsBoolean();
		String tag = !ready ? (entry == null ? "Set up" : adapter.unfinishedTag()) : offline ? "Offline" : enabled ? "Active" : "Off";
		int tagColor = !ready ? ui.theme.danger() : !offline && enabled ? ui.theme.success() : ui.theme.textMuted();
		int tagW = ui.tag(tag, cx + cw - 7, cy + 9, tagColor);
		ui.text(ui.trim(name, cw - 34 - tagW - 6), cx + 26, cy + 9, ui.theme.text());

		String detail = ready
				? adapter.detail(rule)
				: "Click to finish setting up";
		ui.text(ui.trim(detail, cw - 14), cx + 7, cy + 22, ui.theme.textMuted());

		// Meter, e.g. how much of a 64 stack the rule's amount covers.
		int meterW = cw - 14 - ToggleSwitch.WIDTH - 10;
		float[] m = adapter.meter(rule);
		ui.fill(cx + 7, cy + 35, cx + 7 + meterW, cy + 38, ui.theme.track());
		ui.fill(cx + 7 + Math.round(meterW * m[0]), cy + 35, cx + 7 + Math.max(Math.round(meterW * m[0]) + 2, Math.round(meterW * m[1])), cy + 38,
				live ? ui.theme.accent() : ui.theme.textMuted());

		Anim knob = knobs.computeIfAbsent(rule, r -> new Anim(adapter.enabled(r) ? 1 : 0, 18));
		boolean off = locked.getAsBoolean();
		knob.set(enabled && !off ? 1 : 0);
		float k = Anim.easeInOut(knob.update(ui.dt));
		int sx = cx + cw - ToggleSwitch.WIDTH - 6;
		int sy = cy + CARD_H - ToggleSwitch.HEIGHT - 5;
		float base = ui.alpha;
		if (off) ui.alpha = base * 0.4f;
		ToggleSwitch.paint(ui, sx, sy, k, 0);
		ui.alpha = base;
	}

	private void drawAdd(Ui ui, int cx, int cy, int cw, float hv) {
		int color = Anim.lerpColor(ui.theme.border(), ui.theme.accent(), hv);
		if (hv > 0.01f) ui.round(cx, cy, cw, CARD_H, 3, (ui.theme.surface() & 0x00FFFFFF) | (Math.round(hv * 0xAA) << 24));
		ui.dashed(cx, cy, cw, CARD_H, color);
		int textColor = Anim.lerpColor(ui.theme.textMuted(), ui.theme.accent(), hv);
		ui.textCentered("+ Add item", cx + cw / 2, cy + CARD_H / 2 - 8, textColor);
		ui.textCentered(adapter.addHint(rules.isEmpty()), cx + cw / 2, cy + CARD_H / 2 + 3, ui.theme.textMuted());
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
			R rule = rules.get(i);
			int sx = cx + cw - ToggleSwitch.WIDTH - 6;
			int sy = cy + CARD_H - ToggleSwitch.HEIGHT - 5;
			if (mx >= sx - 3 && mx < sx + ToggleSwitch.WIDTH + 3 && my >= sy - 3 && my < sy + ToggleSwitch.HEIGHT + 3) {
				if (locked.getAsBoolean()) return true;
				boolean next = !adapter.enabled(rule);
				adapter.setEnabled(rule, next);
				UiSounds.toggle(next);
				onChanged.accept(rule);
			} else {
				UiSounds.click();
				onEdit.accept(rule);
			}
			return true;
		}
		return false;
	}

	/** Index of the rule card under the mouse, or -1 (the add card and empty space give -1). */
	public int cardAt(double mx, double my) {
		if (!contains(mx, my)) return -1;
		int off = Math.round(scroll.get());
		int cw = cardW();
		for (int i = 0; i < rules.size(); i++) {
			int cx = cellX(i);
			int cy = cellY(i, off);
			if (mx >= cx && mx < cx + cw && my >= cy && my < cy + CARD_H) return i;
		}
		return -1;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double amount) {
		if (!contains(mx, my) || maxScroll() == 0) return false;
		scrollTarget -= (float) amount * 20;
		return true;
	}
}
