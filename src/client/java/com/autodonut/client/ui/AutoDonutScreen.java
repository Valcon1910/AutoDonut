package com.autodonut.client.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
import com.autodonut.client.ui.widget.FeatureCard;
import com.autodonut.client.ui.widget.RuleGrid;
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
	private enum Page { HOME, AUCTION, SAFETY, EDIT }

	private static final float OPEN_MS = 220f;
	private static final float CLOSE_MS = 160f;
	private static final int RESULT_ROW_H = 18;
	private static final int MAX_RESULTS_SHOWN = 6;
	private static final int TOP = 28;
	private static final int NAV_ITEM_H = 18;
	private static final int NAV_SECTION_H = 14;
	/** Sidebar layout: section headers (page == null) and the pages under them. */
	private record NavEntry(String label, Page page) { }
	private static final NavEntry[] NAV = {
			new NavEntry("AUTODONUT", null),
			new NavEntry("Home", Page.HOME),
			new NavEntry("FEATURES", null),
			new NavEntry("Auto Auction", Page.AUCTION),
			new NavEntry("SYSTEM", null),
			new NavEntry("Safety", Page.SAFETY),
	};

	private final AutoDonutConfig cfg = AutoDonutConfig.get();
	private final Ui ui = new Ui();
	private final Anim themeAnim;
	private final Anim navAnim = new Anim(0, 16);
	private final Anim pageAnim = new Anim(1, 11);
	private final Anim closeHover = new Anim(0, 16);
	private final Anim[] navHover = new Anim[NAV.length];
	/** Per sidebar section: 1 = expanded, 0 = collapsed. Only section indices are used. */
	private final Anim[] sectionOpen = new Anim[NAV.length];
	private final List<Widget> widgets = new ArrayList<>();
	private final List<Widget> chrome = new ArrayList<>();

	private final long openedAt = System.nanoTime();
	private long closingAt = -1;
	private long lastFrame = System.nanoTime();
	private float openProgress;

	private Page page = Page.HOME;
	private AuctionRule editing;
	private TextField searchField;
	private List<ItemIndex.Entry> results = List.of();
	private int resultScroll;

	private int px, py, pw, ph, sw;
	/** The panel is drawn at its own pixel-perfect scale so huge GUI scales don't cramp it. */
	private float uiScale = 1f;
	private int vw, vh;

	public AutoDonutScreen() {
		super(Component.literal("AutoDonut"));
		themeAnim = new Anim(cfg.darkMode ? 0 : 1, 7);
		for (int i = 0; i < navHover.length; i++) {
			navHover[i] = new Anim(0, 16);
			sectionOpen[i] = new Anim(1, 14);
		}
	}

	// ---------------------------------------------------------------- layout

	@Override
	protected void init() {
		ui.font = font;
		computeScale();
		pw = Math.min(560, vw - 16);
		ph = Math.min(340, vh - 16);
		px = (vw - pw) / 2;
		py = (vh - ph) / 2;
		sw = Math.max(90, Math.min(120, Math.round(pw * 0.24f)));
		buildChrome();
		buildPage();
	}

	/**
	 * Picks the largest whole-number pixel scale (<= the game's GUI scale) that still gives the
	 * panel at least 560x340 units, then expresses it relative to the GUI scale.
	 */
	private void computeScale() {
		int fbW = minecraft.getWindow().getWidth();
		int fbH = minecraft.getWindow().getHeight();
		int guiScale = Math.max(1, Math.round(fbW / (float) width));
		int n = Math.max(1, Math.min(guiScale, Math.min(fbW / 576, fbH / 356)));
		uiScale = n / (float) guiScale;
		vw = Math.round(width / uiScale);
		vh = Math.round(height / uiScale);
	}

	private int contentX() {
		return px + sw + 12;
	}

	private int contentW() {
		return pw - sw - 24;
	}

	/** Top of the page body, below the page header and its divider. */
	private int bodyTop() {
		return py + TOP + 42;
	}

	private int sectionOf(int index) {
		for (int i = index; i >= 0; i--) {
			if (NAV[i].page() == null) return i;
		}
		return 0;
	}

	/** How expanded the section containing this entry is (always 1 for headers). */
	private float navOpenness(int index) {
		return NAV[index].page() == null ? 1f : Anim.easeInOut(sectionOpen[sectionOf(index)].get());
	}

	private int navY(int index) {
		float y = py + TOP + 8;
		for (int i = 0; i < index; i++) {
			y += NAV[i].page() == null ? NAV_SECTION_H + 2 : (NAV_ITEM_H + 2) * navOpenness(i);
		}
		return Math.round(y);
	}

	private int closeX() {
		return px + pw - 8 - 16;
	}

	private int closeY() {
		return py + (TOP - 16) / 2;
	}

	private void buildChrome() {
		chrome.clear();
		ThemeSwitch theme = new ThemeSwitch(() -> cfg.darkMode, dark -> {
			cfg.darkMode = dark;
			AutoDonutConfig.save();
		});
		theme.bounds(closeX() - 8 - 34, py + (TOP - 16) / 2, 34, 16);
		chrome.add(theme);
	}

	private void buildPage() {
		widgets.clear();
		searchField = null;
		results = List.of();
		int x = contentX();
		int w = contentW();
		int top = bodyTop();

		switch (page) {
			case HOME -> {
				AutoAuctionController auction = AutoAuctionController.get();
				List<FeatureCard> cards = List.of(
						new FeatureCard("Auto Auction", "Lists matching items on /ah for you",
								auction::status, () -> cfg.autoAuctionEnabled, v -> {
									cfg.autoAuctionEnabled = v;
									AutoDonutConfig.save();
								}, () -> setPage(Page.AUCTION)),
						new FeatureCard("HUD Status", "Corner pill while Auto Auction runs",
								() -> "", () -> cfg.showHud, v -> {
									cfg.showHud = v;
									AutoDonutConfig.save();
								}, null),
						new FeatureCard("Donut SMP Only", "Features stay idle on other servers",
								() -> ServerContext.isOnDonut() ? "Connected" : "Not connected", () -> cfg.onlyOnDonut, v -> {
									cfg.onlyOnDonut = v;
									AutoDonutConfig.save();
								}, () -> setPage(Page.SAFETY))
				);
				int introH = introHeight(w);
				int cardsTop = top + introH + 22;
				int cols = w >= 360 ? cards.size() : w >= 240 ? 2 : 1;
				int gap = 8;
				int cw = (w - gap * (cols - 1)) / cols;
				int ch = 66;
				for (int i = 0; i < cards.size(); i++) {
					cards.get(i).bounds(x + (i % cols) * (cw + gap), cardsTop + (i / cols) * (ch + gap), cw, ch);
					widgets.add(cards.get(i));
				}
			}
			case AUCTION -> {
				ToggleSwitch master = new ToggleSwitch(() -> cfg.autoAuctionEnabled, v -> {
					cfg.autoAuctionEnabled = v;
					AutoDonutConfig.save();
				});
				master.bounds(x + w - ToggleSwitch.WIDTH - 8, top + 8, ToggleSwitch.WIDTH, ToggleSwitch.HEIGHT);
				widgets.add(master);

				RuleGrid grid = new RuleGrid(cfg.rules, this::editRule, this::addRule, AutoDonutConfig::save);
				grid.bounds(x, top + 50, w, py + ph - 8 - (top + 50));
				widgets.add(grid);
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
				int avail = py + ph - 8 - top;
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
		searchField.bounds(x, top + 9, w, 18);
		widgets.add(searchField);

		int half = (w - 6) / 2;
		TextField price = new TextField("e.g. 1.5k or 250000", rule.priceText, 16, PriceFormat::isPriceChar, t -> rule.priceText = t).prefix("$ ");
		price.bounds(x, top + 40, half, 18);
		widgets.add(price);
		widgets.add(new Segmented(new String[]{"Per item", "Per stack"}, () -> rule.pricePerItem ? 0 : 1,
				i -> rule.pricePerItem = i == 0).bounds(x + half + 6, top + 40, w - half - 6, 18));

		QuantityMode[] modes = QuantityMode.values();
		String[] labels = new String[modes.length];
		for (int i = 0; i < modes.length; i++) labels[i] = modes[i].label();
		widgets.add(new Segmented(labels, () -> rule.mode.ordinal(), i -> rule.mode = modes[i]).bounds(x, top + 71, w, 18));

		widgets.add(new Slider(1, 64, () -> rule.amount, v -> rule.amount = v, v -> v + "x").labelWidth(28)
				.bounds(x + 48, top + 95, w - 48, 14));

		int by = Math.max(top + 142, py + ph - 26);
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
		mouseX = Math.round(mouseX / uiScale);
		mouseY = Math.round(mouseY / uiScale);
		graphics.pose().pushMatrix();
		graphics.pose().scale(uiScale);
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

	private int activeNavIndex() {
		Page target = page == Page.EDIT ? Page.AUCTION : page;
		for (int i = 0; i < NAV.length; i++) {
			if (NAV[i].page() == target) return i;
		}
		return 1;
	}

	private void drawPanel(int mx, int my) {
		Theme t = ui.theme;
		for (int i = 3; i >= 1; i--) {
			ui.round(px - i * 2, py - i * 2 + 2, pw + i * 4, ph + i * 4, 6 + i * 2, t.shadow() & 0x22FFFFFF);
		}
		ui.card(px, py, pw, ph, 4, t.panel(), t.border());

		// Top bar
		ui.round(px + 1, py + 1, pw - 2, TOP, 3, t.sidebar());
		ui.fill(px + 1, py + TOP - 3, px + pw - 1, py + TOP, t.sidebar());
		ui.fill(px + 1, py + TOP, px + pw - 1, py + TOP + 1, t.border());
		int by = py + TOP / 2;
		ui.logo(px + 8, by - 9, 18);
		ui.bold("Auto", px + 31, by - 4, t.text());
		ui.bold("Donut", px + 31 + ui.boldWidth("Auto"), by - 4, t.accent());
		int vx = px + 31 + ui.boldWidth("AutoDonut") + 6;
		String ver = "v" + AutoDonutClient.version();
		ui.round(vx, by - 6, ui.width(ver) + 8, 12, 3, t.surface());
		ui.text(ver, vx + 4, by - 3, t.textMuted());

		// Sidebar
		ui.fill(px + 1, py + TOP + 1, px + sw, py + ph - 4, t.sidebar());
		ui.round(px + 1, py + ph - 8, sw - 1, 7, 3, t.sidebar());
		ui.fill(px + sw, py + TOP + 1, px + sw + 1, py + ph - 1, t.border());

		for (Anim a : sectionOpen) a.update(ui.dt);
		int active = activeNavIndex();
		float activeOpen = navOpenness(active);
		navAnim.set(navY(active));
		if (navAnim.get() == 0) navAnim.snap(navY(active));
		int hy = Math.round(navAnim.update(ui.dt));
		float baseAlpha = ui.alpha;
		ui.alpha = baseAlpha * activeOpen;
		ui.round(px + 5, hy, sw - 10, NAV_ITEM_H, 2, Anim.lerpColor(t.sidebar(), t.accent(), 0.10f));
		ui.outline(px + 5, hy, sw - 10, NAV_ITEM_H, 1, t.accent());
		ui.alpha = baseAlpha;

		for (int i = 0; i < NAV.length; i++) {
			NavEntry entry = NAV[i];
			int iy = navY(i);
			if (entry.page() == null) {
				// Clickable section header with an arrow: down when open, right when collapsed
				boolean overHeader = mx >= px + 5 && mx < px + sw - 5 && my >= iy && my < iy + NAV_SECTION_H;
				navHover[i].set(overHeader ? 1 : 0);
				navHover[i].update(ui.dt);
				int hc = Anim.lerpColor(t.textMuted(), t.text(), navHover[i].get());
				boolean open = sectionOpen[i].target() > 0.5f;
				int ax = px + 9, ay = iy + 3;
				if (open) {
					ui.fill(ax, ay + 1, ax + 5, ay + 2, hc);
					ui.fill(ax + 1, ay + 2, ax + 4, ay + 3, hc);
					ui.fill(ax + 2, ay + 3, ax + 3, ay + 4, hc);
				} else {
					ui.fill(ax + 1, ay, ax + 2, ay + 5, hc);
					ui.fill(ax + 2, ay + 1, ax + 3, ay + 4, hc);
					ui.fill(ax + 3, ay + 2, ax + 4, ay + 3, hc);
				}
				ui.text(entry.label(), px + 17, iy + 2, hc);
				continue;
			}
			float open = navOpenness(i);
			if (open < 0.05f) continue;
			ui.alpha = baseAlpha * open;
			boolean hovered = i != active && mx >= px + 5 && mx < px + sw - 5 && my >= iy && my < iy + NAV_ITEM_H;
			navHover[i].set(hovered ? 1 : 0);
			navHover[i].update(ui.dt);
			int color = i == active ? t.text() : Anim.lerpColor(t.textMuted(), t.text(), navHover[i].get());
			ui.text(entry.label(), px + 14 + Math.round(navHover[i].get() * 2), iy + 5, color);
			if (entry.page() == Page.AUCTION) {
				ui.circle(px + sw - 14, iy + 9, 2, AutoAuctionController.get().isActive() ? t.success() : t.track());
			}
			ui.alpha = baseAlpha;
		}

		boolean donut = ServerContext.isOnDonut();
		int fy = py + ph - 24;
		ui.fill(px + 8, fy - 6, px + sw - 8, fy - 5, t.border());
		ui.circle(px + 11, fy + 4, 2, donut ? t.success() : t.track());
		ui.text(donut ? "Donut SMP" : "Not connected", px + 17, fy, donut ? t.text() : t.textMuted());
		ui.text("K to close", px + 8, fy + 11, t.textMuted());

		for (Widget w : chrome) w.render(ui, mx, my);

		int cx = closeX(), cy = closeY();
		boolean overClose = mx >= cx && mx < cx + 16 && my >= cy && my < cy + 16;
		closeHover.set(overClose ? 1 : 0);
		closeHover.update(ui.dt);
		ui.round(cx, cy, 16, 16, 3, Anim.lerpColor(t.sidebar(), t.surfaceHover(), closeHover.get()));
		int xc = Anim.lerpColor(t.textMuted(), t.text(), closeHover.get());
		for (int i = 0; i < 6; i++) {
			ui.fill(cx + 5 + i, cy + 5 + i, cx + 6 + i, cy + 6 + i, xc);
			ui.fill(cx + 10 - i, cy + 5 + i, cx + 11 - i, cy + 6 + i, xc);
		}
	}

	/** Page header (accent bar, title, description, divider) plus static parts of each page. */
	private void drawPageDecor() {
		Theme t = ui.theme;
		int x = contentX();
		int w = contentW();
		String title = switch (page) {
			case HOME -> "Home";
			case AUCTION -> "Auto Auction";
			case SAFETY -> "Safety";
			case EDIT -> "Edit item";
		};
		String subtitle = switch (page) {
			case HOME -> "Your Donut SMP companion.";
			case AUCTION -> "Pick items to sell. Matching stacks are listed on /ah automatically.";
			case SAFETY -> "Pacing that keeps every action irregular and human.";
			case EDIT -> "Choose the item, its price and which stack sizes to sell.";
		};
		int hy = py + TOP + 8;
		ui.fill(x, hy, x + 2, hy + 21, t.accent());
		ui.text(title, x + 8, hy + 1, t.text());
		ui.text(ui.trim(subtitle, w - 8), x + 8, hy + 12, t.textMuted());
		ui.fill(x, hy + 27, x + w, hy + 28, t.border());

		int top = bodyTop();
		switch (page) {
			case AUCTION -> {
				AutoAuctionController auction = AutoAuctionController.get();
				boolean active = auction.isActive();
				ui.round(x, top, w, 42, 3, t.surface());
				if (cfg.autoAuctionEnabled) ui.outline(x, top, w, 42, 1, t.accent());
				ui.text("AUTO AUCTION", x + 8, top + 7, cfg.autoAuctionEnabled ? t.accent() : t.text());
				String tag = active ? "RUNNING" : cfg.autoAuctionEnabled ? "WAITING" : "OFF";
				int tagColor = active ? t.success() : cfg.autoAuctionEnabled ? t.textMuted() : t.textMuted();
				ui.text(tag, x + 14 + ui.width("AUTO AUCTION"), top + 7, tagColor);
				ui.text(ui.trim(auction.status(), w - 50), x + 8, top + 19, t.textMuted());

				int hour = auction.listedLastHour();
				String count = hour + " / " + cfg.maxListingsPerHour;
				ui.text("THIS HOUR", x + 8, top + 30, t.textMuted());
				int mx0 = x + 14 + ui.width("THIS HOUR");
				int mw = w - (mx0 - x) - ui.width(count) - 16;
				ui.meter(mx0, top + 33, mw, hour / (float) cfg.maxListingsPerHour, t.accent());
				ui.textRight(count, x + w - 8, top + 30, t.text());
			}
			case EDIT -> {
				AuctionRule rule = editing;
				if (rule == null) return;
				ui.text("ITEM", x, top, t.textMuted());
				ui.text("PRICE", x, top + 31, t.textMuted());
				ui.text("QUANTITY", x, top + 62, t.textMuted());
				ui.text("Amount", x, top + 98, t.text());

				ItemIndex.Entry entry = ItemIndex.byId(rule.itemId);
				if (entry != null && searchField != null && searchField.text().equals(entry.name())) {
					ui.item(entry.stack(), x + w - 19, top + 10);
				}
				drawPreview(rule, entry, x, top + 114, w);
			}
			case HOME -> {
				int ih = introHeight(w);
				ui.round(x, top, w, ih, 3, t.surface());
				ui.fill(x, top, x + 2, top + ih, t.accent());
				List<String> lines = ui.wrap(INTRO, w - 20);
				for (int i = 0; i < lines.size(); i++) {
					ui.text(lines.get(i), x + 10, top + 7 + i * 10, i == 0 ? t.text() : t.textMuted());
				}
				ui.text("FEATURES", x, top + ih + 9, t.textMuted());
				ui.fill(x + ui.width("FEATURES") + 6, top + ih + 13, x + w, top + ih + 14, t.border());
			}
			default -> { }
		}
	}

	private static final String INTRO = "AutoDonut is a client-side helper for Donut SMP. It handles repetitive jobs, "
			+ "like listing items on the auction house, quietly in the background with randomised, human-like timing. "
			+ "Nothing is installed on the server. Switch each feature on or off below, or open it from the sidebar to "
			+ "change its settings.";

	private int introHeight(int w) {
		return ui.wrap(INTRO, w - 20).size() * 10 + 14;
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
		double mx = event.x() / uiScale;
		double my = event.y() / uiScale;

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
		int cx = closeX(), cy = closeY();
		if (mx >= cx && mx < cx + 16 && my >= cy && my < cy + 16) {
			onClose();
			return true;
		}
		// Navigation
		for (int i = 0; i < NAV.length; i++) {
			int iy = navY(i);
			if (NAV[i].page() == null) {
				if (mx >= px + 5 && mx < px + sw - 5 && my >= iy && my < iy + NAV_SECTION_H) {
					sectionOpen[i].set(sectionOpen[i].target() > 0.5f ? 0 : 1);
					return true;
				}
				continue;
			}
			if (sectionOpen[sectionOf(i)].target() < 0.5f) continue;
			if (mx >= px + 5 && mx < px + sw - 5 && my >= iy && my < iy + NAV_ITEM_H) {
				if (NAV[i].page() != page) setPage(NAV[i].page());
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
		for (Widget w : widgets) w.mouseReleased(event.x() / uiScale, event.y() / uiScale);
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		for (Widget w : widgets) w.mouseDragged(event.x() / uiScale, event.y() / uiScale);
		return true;
	}

	@Override
	public boolean mouseScrolled(double rawX, double rawY, double scrollX, double scrollY) {
		if (inputBlocked()) return true;
		double mx = rawX / uiScale;
		double my = rawY / uiScale;
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
		// Keep the editor's rule alive while the close animation still draws it; removed() cleans up.
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
