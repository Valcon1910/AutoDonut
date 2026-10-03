package com.autodonut.client.ui;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import com.autodonut.client.AutoDonutClient;
import com.autodonut.client.ServerContext;
import com.autodonut.client.auction.AutoAuctionController;
import com.autodonut.client.auction.ItemIndex;
import com.autodonut.client.config.AuctionRule;
import com.autodonut.client.config.AutoDonutConfig;
import com.autodonut.client.config.PriceFormat;
import com.autodonut.client.config.QuantityMode;
import com.autodonut.client.ui.widget.RuleList;
import com.autodonut.client.ui.widget.Segmented;
import com.autodonut.client.ui.widget.SettingRow;
import com.autodonut.client.ui.widget.Slider;
import com.autodonut.client.ui.widget.TextField;
import com.autodonut.client.ui.widget.ThemeSwitch;
import com.autodonut.client.ui.widget.ToggleSwitch;
import com.autodonut.client.ui.widget.UiButton;
import com.autodonut.client.ui.widget.Widget;

/** The AutoDonut control panel, opened with K. Fully custom drawn with animated transitions. */
public class AutoDonutScreen extends Screen {
	private enum Page { AUCTION, SAFETY, EDIT }

	private static final float OPEN_MS = 220f;
	private static final float CLOSE_MS = 160f;
	private static final int RESULT_ROW_H = 18;
	private static final int MAX_RESULTS_SHOWN = 6;
	private static final String[] NAV = {"Auto Auction", "Safety"};

	private final AutoDonutConfig cfg = AutoDonutConfig.get();
	private final Ui ui = new Ui();
	private final Anim themeAnim;
	private final Anim navAnim = new Anim(0, 16);
	private final Anim pageAnim = new Anim(1, 11);
	private final Anim closeHover = new Anim(0, 16);
	private final Anim[] navHover = {new Anim(0, 16), new Anim(0, 16)};
	private final List<Widget> widgets = new ArrayList<>();
	private final List<Widget> chrome = new ArrayList<>();

	private final long openedAt = System.nanoTime();
	private long closingAt = -1;
	private long lastFrame = System.nanoTime();
	private float openProgress;

	private Page page = Page.AUCTION;
	private AuctionRule editing;
	private TextField searchField;
	private List<ItemIndex.Entry> results = List.of();
	private int resultScroll;

	private int px, py, pw, ph, sw;

	public AutoDonutScreen() {
		super(Component.literal("AutoDonut"));
		themeAnim = new Anim(cfg.darkMode ? 0 : 1, 7);
	}

	// ---------------------------------------------------------------- layout

	@Override
	protected void init() {
		ui.font = font;
		pw = Math.min(430, width - 20);
		ph = Math.min(270, height - 20);
		px = (width - pw) / 2;
		py = (height - ph) / 2;
		sw = Math.max(92, Math.min(112, Math.round(pw * 0.26f)));
		buildChrome();
		buildPage();
	}

	private int contentX() {
		return px + sw + 14;
	}

	private int contentW() {
		return pw - sw - 28;
	}

	private void buildChrome() {
		chrome.clear();
		ThemeSwitch theme = new ThemeSwitch(() -> cfg.darkMode, dark -> {
			cfg.darkMode = dark;
			AutoDonutConfig.save();
		});
		theme.bounds(px + pw - 14 - 16 - 8 - 34, py + 12, 34, 16);
		chrome.add(theme);
	}

	private void buildPage() {
		widgets.clear();
		searchField = null;
		results = List.of();
		int x = contentX();
		int w = contentW();
		int top = py + 44;

		switch (page) {
			case AUCTION -> {
				ToggleSwitch master = new ToggleSwitch(() -> cfg.autoAuctionEnabled, v -> {
					cfg.autoAuctionEnabled = v;
					AutoDonutConfig.save();
				});
				master.bounds(x + w - ToggleSwitch.WIDTH - 10, top + 10, ToggleSwitch.WIDTH, ToggleSwitch.HEIGHT);
				widgets.add(master);

				String add = "+ Add item";
				int addW = UiButton.widthFor(ui, add);
				widgets.add(new UiButton(add, UiButton.Style.PRIMARY, this::addRule).bounds(x + w - addW, top + 40, addW, 16));

				RuleList list = new RuleList(cfg.rules, this::editRule, AutoDonutConfig::save);
				list.bounds(x, top + 62, w, py + ph - 12 - (top + 62));
				widgets.add(list);
			}
			case SAFETY -> {
				List<SettingRow> rows = List.of(
						new SettingRow("Only on Donut SMP", "Stay idle on every other server",
								toggle(() -> cfg.onlyOnDonut, v -> cfg.onlyOnDonut = v), ToggleSwitch.WIDTH),
						new SettingRow("Minimum delay", "Shortest wait between listings",
								new Slider(3, 120, () -> cfg.minDelaySeconds, v -> {
									cfg.minDelaySeconds = v;
									if (cfg.maxDelaySeconds < v) cfg.maxDelaySeconds = v;
								}, v -> v + "s"), 110),
						new SettingRow("Maximum delay", "Longest wait between listings",
								new Slider(5, 300, () -> cfg.maxDelaySeconds, v -> {
									cfg.maxDelaySeconds = v;
									if (cfg.minDelaySeconds > v) cfg.minDelaySeconds = Math.max(3, v);
								}, v -> v + "s"), 110),
						new SettingRow("Reaction time", "Max wait after picking up an item",
								new Slider(1, 15, () -> cfg.maxReactionSeconds, v -> cfg.maxReactionSeconds = v, v -> v + "s"), 110),
						new SettingRow("Listings per hour", "Hard cap, resets on a rolling hour",
								new Slider(1, 60, () -> cfg.maxListingsPerHour, v -> cfg.maxListingsPerHour = v, v -> Integer.toString(v)), 110),
						new SettingRow("Random breaks", "Sometimes pause for a few minutes",
								toggle(() -> cfg.randomBreaks, v -> cfg.randomBreaks = v), ToggleSwitch.WIDTH),
						new SettingRow("Pause in menus", "Wait while chests or chat are open",
								toggle(() -> cfg.pauseInMenus, v -> cfg.pauseInMenus = v), ToggleSwitch.WIDTH),
						new SettingRow("HUD status", "Show what Auto Auction is doing",
								toggle(() -> cfg.showHud, v -> cfg.showHud = v), ToggleSwitch.WIDTH)
				);
				int avail = py + ph - 10 - top;
				int gap = 3;
				int rowH = Math.min(26, (avail + gap) / rows.size() - gap);
				for (int i = 0; i < rows.size(); i++) {
					rows.get(i).bounds(x, top + i * (rowH + gap), w, rowH);
					widgets.add(rows.get(i));
				}
			}
			case EDIT -> buildEditor(x, w, top);
		}
	}

	private ToggleSwitch toggle(java.util.function.BooleanSupplier get, java.util.function.Consumer<Boolean> set) {
		return new ToggleSwitch(get, v -> {
			set.accept(v);
			AutoDonutConfig.save();
		});
	}

	private void buildEditor(int x, int w, int top) {
		AuctionRule rule = editing;
		ItemIndex.Entry current = ItemIndex.byId(rule.itemId);

		searchField = new TextField("Search items, e.g. diamond", current == null ? "" : current.name(), 40, c -> true, text -> {
			resultScroll = 0;
			results = ItemIndex.search(text, 60);
		}).searchIcon();
		searchField.bounds(x, top + 10, w, 18);
		widgets.add(searchField);

		int half = (w - 6) / 2;
		TextField price = new TextField("e.g. 1.5k or 250000", rule.priceText, 16, PriceFormat::isPriceChar, t -> rule.priceText = t).prefix("$ ");
		price.bounds(x, top + 44, half, 18);
		widgets.add(price);
		widgets.add(new Segmented(new String[]{"Per item", "Per stack"}, () -> rule.pricePerItem ? 0 : 1,
				i -> rule.pricePerItem = i == 0).bounds(x + half + 6, top + 44, w - half - 6, 18));

		QuantityMode[] modes = QuantityMode.values();
		String[] labels = new String[modes.length];
		for (int i = 0; i < modes.length; i++) labels[i] = modes[i].label();
		widgets.add(new Segmented(labels, () -> rule.mode.ordinal(), i -> rule.mode = modes[i]).bounds(x, top + 78, w, 18));

		widgets.add(new Slider(1, 64, () -> rule.amount, v -> rule.amount = v, v -> v + "x").labelWidth(28)
				.bounds(x + 48, top + 102, w - 48, 14));

		int by = Math.max(top + 156, py + ph - 28);
		int doneW = 60;
		widgets.add(new UiButton("Done", UiButton.Style.PRIMARY, this::finishEditing).bounds(x + w - doneW, by, doneW, 18));
		widgets.add(new UiButton("Delete", UiButton.Style.DANGER, () -> {
			cfg.rules.remove(rule);
			AutoDonutConfig.save();
			editing = null;
			setPage(Page.AUCTION);
		}).bounds(x, by, 56, 18));
	}

	private void setPage(Page next) {
		if (page == Page.EDIT && next != Page.EDIT) cleanUpEditing();
		page = next;
		pageAnim.snap(0);
		pageAnim.set(1);
		buildPage();
	}

	private void addRule() {
		AuctionRule rule = new AuctionRule();
		cfg.rules.add(rule);
		editRule(rule);
		if (searchField != null) searchField.setFocused(true);
	}

	private void editRule(AuctionRule rule) {
		editing = rule;
		setPage(Page.EDIT);
	}

	private void finishEditing() {
		setPage(Page.AUCTION);
	}

	/** Rules left without an item are dropped; everything else is kept and saved. */
	private void cleanUpEditing() {
		if (editing != null && editing.itemId.isEmpty()) cfg.rules.remove(editing);
		editing = null;
		AutoDonutConfig.save();
	}

	private boolean resultsVisible() {
		return searchField != null && searchField.isFocused() && !results.isEmpty();
	}

	// ---------------------------------------------------------------- rendering

	private void updateFrame() {
		long now = System.nanoTime();
		ui.dt = Math.min(0.1f, (now - lastFrame) / 1_000_000_000f);
		lastFrame = now;
		if (closingAt >= 0) {
			openProgress = 1f - Anim.easeInCubic((now - closingAt) / 1_000_000f / CLOSE_MS);
		} else {
			openProgress = Anim.easeOutCubic((now - openedAt) / 1_000_000f / OPEN_MS);
		}
		themeAnim.set(cfg.darkMode ? 0 : 1);
		ui.theme = Theme.lerp(Theme.DARK, Theme.LIGHT, Anim.easeInOut(themeAnim.update(ui.dt)));
		ui.font = font;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		updateFrame();
		ui.g = graphics;
		ui.alpha = openProgress;
		ui.fill(0, 0, width, height, ui.theme.scrim());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		ui.g = graphics;
		ui.font = font;
		ui.alpha = openProgress;

		float scale = 0.94f + 0.06f * openProgress;
		float cx = px + pw / 2f;
		float cy = py + ph / 2f;
		graphics.pose().pushMatrix();
		graphics.pose().translate(cx, cy + (1f - openProgress) * 6f);
		graphics.pose().scale(scale);
		graphics.pose().translate(-cx, -cy);

		drawPanel(mouseX, mouseY);

		float pe = Anim.easeOutCubic(pageAnim.update(ui.dt));
		ui.alpha = openProgress * pe;
		graphics.pose().pushMatrix();
		graphics.pose().translate((1f - pe) * 10f, 0);
		drawPageDecor();
		for (Widget widget : widgets) widget.render(ui, mouseX, mouseY);
		if (resultsVisible()) drawResults(mouseX, mouseY);
		graphics.pose().popMatrix();

		graphics.pose().popMatrix();
	}

	private void drawPanel(int mx, int my) {
		Theme t = ui.theme;
		// Soft shadow
		for (int i = 4; i >= 1; i--) {
			ui.round(px - i * 2, py - i * 2 + 3, pw + i * 4, ph + i * 4, 12 + i * 2, t.shadow() & 0x22FFFFFF);
		}
		ui.card(px, py, pw, ph, 10, t.panel(), t.border());

		// Sidebar
		ui.round(px + 1, py + 1, sw + 10, ph - 2, 9, t.sidebar());
		ui.fill(px + sw, py + 1, px + sw + 11, py + ph - 1, t.panel());
		ui.fill(px + sw, py + 10, px + sw + 1, py + ph - 10, t.border());

		// Brand: a little donut
		int bx = px + 18, by = py + 19;
		ui.circle(bx, by, 7, t.accent());
		ui.circle(bx, by, 5, Anim.lerpColor(t.accent(), 0xFFFFFFFF, 0.25f));
		ui.circle(bx, by, 2, t.sidebar());
		ui.text("AutoDonut", px + 30, py + 12, t.text());
		ui.text("AutoDonut", px + 31, py + 12, t.text());
		ui.text("v" + AutoDonutClient.version(), px + 30, py + 22, t.textMuted());

		// Navigation
		int navY = py + 44;
		int active = page == Page.SAFETY ? 1 : 0;
		navAnim.set(active);
		float n = navAnim.update(ui.dt);
		ui.round(px + 8, navY + Math.round(n * 22), sw - 16, 18, 5, t.surface());
		ui.round(px + 8, navY + Math.round(n * 22) + 4, 2, 10, 1, t.accent());
		for (int i = 0; i < NAV.length; i++) {
			int iy = navY + i * 22;
			boolean hovered = mx >= px + 8 && mx < px + sw - 8 && my >= iy && my < iy + 18;
			navHover[i].set(hovered && i != active ? 1 : 0);
			navHover[i].update(ui.dt);
			if (navHover[i].get() > 0.01f) {
				ui.round(px + 8, iy, sw - 16, 18, 5, (t.surface() & 0x00FFFFFF) | (Math.round(navHover[i].get() * 0x80) << 24));
			}
			int color = i == active ? t.text() : Anim.lerpColor(t.textMuted(), t.text(), navHover[i].get());
			ui.text(NAV[i], px + 16, iy + 5, color);
			if (i == 0) {
				int dot = AutoAuctionController.get().isActive() ? t.success() : t.track();
				ui.circle(px + sw - 16, iy + 9, 2, dot);
			}
		}

		// Footer: connection state and shortcut hint
		boolean donut = ServerContext.isOnDonut();
		int fy = py + ph - 26;
		ui.circle(px + 14, fy + 4, 2, donut ? t.success() : t.track());
		ui.text(donut ? "Donut SMP" : "Not connected", px + 20, fy, donut ? t.text() : t.textMuted());
		ui.text("Press K to close", px + 10, fy + 11, t.textMuted());

		// Header
		int hx = contentX();
		String title = switch (page) {
			case AUCTION -> "Auto Auction";
			case SAFETY -> "Safety";
			case EDIT -> "Edit item";
		};
		String subtitle = switch (page) {
			case AUCTION -> "Lists your items on /ah automatically";
			case SAFETY -> "Pacing that keeps things human";
			case EDIT -> "Pick the item, price and stack size";
		};
		ui.text(title, hx, py + 12, t.text());
		ui.text(title, hx + 1, py + 12, t.text());
		ui.text(ui.trim(subtitle, contentW() - 70), hx, py + 23, t.textMuted());

		for (Widget w : chrome) w.render(ui, mx, my);

		// Close button
		int cx = px + pw - 14 - 16, cy = py + 12;
		boolean overClose = mx >= cx && mx < cx + 16 && my >= cy && my < cy + 16;
		closeHover.set(overClose ? 1 : 0);
		closeHover.update(ui.dt);
		ui.round(cx, cy, 16, 16, 5, Anim.lerpColor(t.panel(), t.surfaceHover(), closeHover.get()));
		int xc = Anim.lerpColor(t.textMuted(), t.text(), closeHover.get());
		for (int i = 0; i < 6; i++) {
			ui.fill(cx + 5 + i, cy + 5 + i, cx + 6 + i, cy + 6 + i, xc);
			ui.fill(cx + 10 - i, cy + 5 + i, cx + 11 - i, cy + 6 + i, xc);
		}
	}

	/** Static text and cards that belong to the current page. */
	private void drawPageDecor() {
		Theme t = ui.theme;
		int x = contentX();
		int w = contentW();
		int top = py + 44;
		switch (page) {
			case AUCTION -> {
				ui.card(x, top, w, 34, 7, t.surface(), t.border());
				boolean active = AutoAuctionController.get().isActive();
				ui.circle(x + 11, top + 12, 3, active ? t.success() : t.track());
				ui.text("Enabled", x + 19, top + 8, t.text());
				String status = AutoAuctionController.get().status();
				int listed = AutoAuctionController.get().listedThisSession();
				if (listed > 0) status += "  -  " + listed + " listed";
				ui.text(ui.trim(status, w - 60), x + 19, top + 20, t.textMuted());

				ui.text("Items", x, top + 45, t.text());
				ui.text(cfg.rules.size() + "", x + ui.width("Items") + 5, top + 45, t.textMuted());
			}
			case EDIT -> {
				AuctionRule rule = editing;
				ui.text("Item", x, top, t.textMuted());
				ui.text("Price", x, top + 34, t.textMuted());
				ui.text("Quantity", x, top + 68, t.textMuted());
				ui.text("Amount", x, top + 105, t.text());

				ItemIndex.Entry entry = ItemIndex.byId(rule.itemId);
				if (entry != null && searchField != null && searchField.text().equals(entry.name())) {
					ui.item(entry.stack(), x + w - 19, top + 11);
				}
				drawPreview(rule, entry, x, top + 124, w);
			}
			default -> { }
		}
	}

	private void drawPreview(AuctionRule rule, ItemIndex.Entry entry, int x, int y, int w) {
		Theme t = ui.theme;
		ui.card(x, y, w, 26, 6, Anim.lerpColor(t.panel(), t.accent(), 0.08f), Anim.lerpColor(t.border(), t.accent(), 0.35f));
		String line1;
		String line2;
		if (entry == null) {
			line1 = "Search for an item above";
			line2 = "Then set the price and stack size";
		} else if (rule.price() <= 0) {
			line1 = "Enter a price, e.g. 500, 1.5k or 2m";
			line2 = "Triggers on " + entry.name() + " stacks of " + rule.mode.label().toLowerCase() + " " + rule.amount;
		} else {
			line1 = "Triggers on stacks of " + rule.mode.label().toLowerCase() + " " + rule.amount;
			int example = switch (rule.mode) {
				case EXACTLY -> rule.amount;
				case LESS_THAN -> Math.max(1, rule.amount - 1);
				case MORE_THAN -> Math.min(64, rule.amount + 1);
			};
			line2 = rule.pricePerItem
					? "A stack of " + example + " lists for $" + PriceFormat.format(rule.totalPrice(example)) + " ($" + PriceFormat.format(rule.price()) + " each)"
					: "Every matching stack lists for $" + PriceFormat.format(rule.price());
		}
		ui.text(ui.trim(line1, w - 12), x + 6, y + 4, t.text());
		ui.text(ui.trim(line2, w - 12), x + 6, y + 15, t.textMuted());
	}

	private void drawResults(int mx, int my) {
		Theme t = ui.theme;
		int x = searchField.x;
		int y = searchField.y + searchField.h + 2;
		int w = searchField.w;
		int shown = Math.min(MAX_RESULTS_SHOWN, results.size());
		int h = shown * RESULT_ROW_H + 4;
		ui.round(x - 1, y + 1, w + 2, h + 2, 7, t.shadow());
		ui.card(x, y, w, h, 6, t.surface(), t.border());
		for (int i = 0; i < shown; i++) {
			int idx = i + resultScroll;
			if (idx >= results.size()) break;
			ItemIndex.Entry e = results.get(idx);
			int ry = y + 2 + i * RESULT_ROW_H;
			boolean hovered = mx >= x && mx < x + w && my >= ry && my < ry + RESULT_ROW_H;
			if (hovered) ui.round(x + 2, ry, w - 4, RESULT_ROW_H, 4, t.surfaceHover());
			ui.item(e.stack(), x + 4, ry + 1);
			ui.text(ui.trim(e.name(), w - 120), x + 24, ry + 5, t.text());
			ui.textRight(ui.trim(e.id(), 90), x + w - 6, ry + 5, t.textMuted());
		}
		if (results.size() > MAX_RESULTS_SHOWN) {
			int barH = Math.max(10, h * MAX_RESULTS_SHOWN / results.size());
			int maxScroll = results.size() - MAX_RESULTS_SHOWN;
			int barY = y + 2 + Math.round((h - 4 - barH) * (resultScroll / (float) maxScroll));
			ui.round(x + w - 4, barY, 2, barH, 1, t.track());
		}
	}

	// ---------------------------------------------------------------- input

	private boolean inputBlocked() {
		return closingAt >= 0;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (inputBlocked()) return true;
		double mx = event.x();
		double my = event.y();

		if (resultsVisible()) {
			int x = searchField.x;
			int y = searchField.y + searchField.h + 2;
			int shown = Math.min(MAX_RESULTS_SHOWN, results.size());
			if (mx >= x && mx < x + searchField.w && my >= y && my < y + shown * RESULT_ROW_H + 4) {
				int idx = (int) ((my - y - 2) / RESULT_ROW_H) + resultScroll;
				if (idx >= 0 && idx < results.size()) selectItem(results.get(idx));
				return true;
			}
		}

		// Close button
		int cx = px + pw - 14 - 16, cy = py + 12;
		if (mx >= cx && mx < cx + 16 && my >= cy && my < cy + 16) {
			onClose();
			return true;
		}
		// Navigation
		for (int i = 0; i < NAV.length; i++) {
			int iy = py + 44 + i * 22;
			if (mx >= px + 8 && mx < px + sw - 8 && my >= iy && my < iy + 18) {
				Page target = i == 0 ? Page.AUCTION : Page.SAFETY;
				if (target != page) setPage(target);
				return true;
			}
		}

		for (Widget w : widgets) w.setFocused(false);
		for (Widget w : chrome) {
			if (w.mouseClicked(mx, my)) return true;
		}
		for (Widget w : widgets) {
			if (w.mouseClicked(mx, my)) return true;
		}
		return true;
	}

	private void selectItem(ItemIndex.Entry entry) {
		editing.itemId = entry.id();
		searchField.setText(entry.name());
		searchField.setFocused(false);
		results = List.of();
		AutoDonutConfig.save();
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		for (Widget w : widgets) w.mouseReleased(event.x(), event.y());
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		for (Widget w : widgets) w.mouseDragged(event.x(), event.y());
		return true;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
		if (inputBlocked()) return true;
		if (resultsVisible()) {
			int max = Math.max(0, results.size() - MAX_RESULTS_SHOWN);
			resultScroll = Math.max(0, Math.min(max, resultScroll - (int) Math.signum(scrollY)));
			return true;
		}
		for (Widget w : widgets) {
			if (w.mouseScrolled(mx, my, scrollY)) return true;
		}
		return true;
	}

	private boolean anyFocused() {
		for (Widget w : widgets) {
			if (w.isFocused()) return true;
		}
		return false;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (inputBlocked()) return true;
		if (anyFocused()) {
			if (event.key() == InputConstants.KEY_ESCAPE) {
				for (Widget w : widgets) w.setFocused(false);
				return true;
			}
			for (Widget w : widgets) {
				if (w.keyPressed(event)) return true;
			}
			return true;
		}
		if (AutoDonutClient.openKey().matches(event)) {
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (inputBlocked()) return true;
		String chars = event.codepointAsString();
		for (Widget w : widgets) {
			if (w.charTyped(chars)) return true;
		}
		return false;
	}

	// ---------------------------------------------------------------- lifecycle

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		if (closingAt >= 0) return;
		if (page == Page.EDIT) cleanUpEditing();
		AutoDonutConfig.save();
		closingAt = System.nanoTime();
	}

	@Override
	public void tick() {
		super.tick();
		if (closingAt >= 0 && (System.nanoTime() - closingAt) / 1_000_000f >= CLOSE_MS) {
			minecraft.gui.setScreen(null);
		}
	}

	@Override
	public void removed() {
		super.removed();
		if (page == Page.EDIT) cleanUpEditing();
		AutoDonutConfig.save();
	}
}
