package com.autodonut.client.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.autodonut.client.AutoDonutClient;
import com.autodonut.client.Compat;
import com.autodonut.client.Lockdown;
import com.autodonut.client.ServerContext;
import com.autodonut.client.auction.AutoAuctionController;
import com.autodonut.client.auction.AutoBuyController;
import com.autodonut.client.auction.ItemIndex;
import com.autodonut.client.config.AuctionRule;
import com.autodonut.client.config.AutoDonutConfig;
import com.autodonut.client.config.BuyRule;
import com.autodonut.client.config.PriceFormat;
import com.autodonut.client.config.QuantityMode;
import com.autodonut.client.ui.widget.ChoiceGrid;
import com.autodonut.client.ui.widget.FeatureCard;
import com.autodonut.client.ui.widget.RangeSlider;
import com.autodonut.client.ui.widget.RuleGrid;
import com.autodonut.client.ui.widget.Segmented;
import com.autodonut.client.ui.widget.SettingRow;
import com.autodonut.client.ui.widget.Slider;
import com.autodonut.client.ui.widget.TextField;
import com.autodonut.client.ui.widget.ThemeSwitch;
import com.autodonut.client.ui.widget.ToggleSwitch;
import com.autodonut.client.ui.widget.UiButton;
import com.autodonut.client.ui.widget.Widget;
import com.autodonut.client.Updater;

/** The AutoDonut control panel, opened with K. Fully custom drawn with animated transitions. */
public class AutoDonutScreen extends Screen {
	private enum Page { HOME, AUCTION, AUTOBUY, SAFETY, APPEARANCE, CHANGELOG, UPDATING, EDIT, BUY_EDIT }

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
			new NavEntry("AutoDonut", null),
			new NavEntry("Home", Page.HOME),
			new NavEntry("Features", null),
			new NavEntry("Auto Auction", Page.AUCTION),
			new NavEntry("Auto Buy", Page.AUTOBUY),
			new NavEntry("System", null),
			new NavEntry("Safety", Page.SAFETY),
			new NavEntry("Appearance", Page.APPEARANCE),
			new NavEntry("Changelog", Page.CHANGELOG),
			new NavEntry("Updating", Page.UPDATING),
	};

	private final AutoDonutConfig cfg = AutoDonutConfig.get();
	private final Ui ui = new Ui();
	private final Anim themeAnim = new Anim(1, 7);
	private Theme themeFrom;
	private Theme themeTo;
	private boolean rebuildPending;
	private Updater.State lastUpdaterState;
	/** Updating page: the version whose changelog is shown, its scroll, and where the text area starts. */
	private int changelogScroll;
	/** Changelog headings drawn last frame (version -> y), for collapsing on click. */
	private final java.util.Map<String, Integer> noteHeadings = new java.util.HashMap<>();
	private int noteLineH, noteBottom;
	private int changelogTop;
	/** One line of a changelog box: 0 = text, 1 = version heading, 2 = muted note. */
	private record NoteLine(String text, int kind, String ver) {
		NoteLine(String text, int kind) {
			this(text, kind, null);
		}
	}
	/** 0 = Exactly (no range row), 1 = Custom (range slider row shown). Drives the editor's layout shift. */
	private final Anim customRow = new Anim(0, 14);
	private int hoveredResult = -1;
	private int lastMouseX, lastMouseY;
	private boolean lastOffline;
	private static final int SAFETY_ROW_H = 26;
	private static final int SAFETY_GAP = 3;
	private List<SettingRow> safetyRows = List.of();
	private final Anim safetyScroll = new Anim(0, 18);
	private float safetyScrollTarget;
	/** In-panel boot-up: start time (-1 = not booting), results per step (null entry = passed). */
	private long bootStartedAt = -1;
	private final List<com.autodonut.client.BootSequence.Failure> bootResults = new ArrayList<>();
	private boolean bootWaiting;
	/** When the post-boot reveal animation started (-1 = none). */
	private long revealAt = -1;
	private static final long BOOT_STEP_MS = 320;
	private int[] appearanceLabels = new int[0];
	/** Invisible vanilla text box that holds keyboard focus so the game sends typed characters. */
	private EditBox inputSink;
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
	/** Auto Buy item open in the buy editor. */
	private BuyRule buyEditing;
	/** Height of the open editor's content (before the chip list grows), for scrolling. */
	private int editorContentH = 148;
	/** Widget that fades and slides in after a mode switch rebuilt the editor (e.g. the budget input). */
	private Widget fadeInWidget;
	private final Anim fadeIn = new Anim(1, 14);
	/** Buy editor: 1 = "Purchase limit" row shown (Pause after purchases on), 0 = folded away. */
	private final Anim limitRow = new Anim(0, 14);
	private Widget limitRowWidget;
	private int limitRowBase = Integer.MAX_VALUE;
	/** Current folded height of the purchase limit row (0 when shown). */
	private int limitCollapse;
	private TextField searchField;
	private List<ItemIndex.Entry> results = List.of();
	private int resultScroll;

	private int px, py, pw, ph, sw;
	/** The panel is drawn at its own pixel-perfect scale so huge GUI scales don't cramp it. */
	private float uiScale = 1f;
	private int vw, vh;

	/** Screen to return to on close (e.g. the title screen), or null in-game. */
	private final Screen parent;

	public AutoDonutScreen() {
		this(null);
	}

	public AutoDonutScreen(Screen parent) {
		super(Component.literal("AutoDonut"));
		this.parent = parent;
		themeTo = Theme.current(cfg);
		UiSounds.open();
		themeFrom = themeTo;
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
		inputSink = new EditBox(font, -2000, -2000, 10, 10, null, Component.empty());
		addWidget(inputSink);
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
			if (page == Page.APPEARANCE) rebuildPending = true;
		});
		theme.bounds(closeX() - 8 - 34, py + (TOP - 16) / 2, 34, 16);
		chrome.add(theme);
		if (Updater.updateAvailable()) {
			String label = updateButtonLabel();
			UiButton update = new UiButton(label, UiButton.Style.PRIMARY, () -> {
				if (Updater.state() == Updater.State.AVAILABLE) {
					Updater.install();
				}
				setPage(Page.UPDATING);
			});
			update.bounds(versionX(), py + (TOP - 14) / 2, UiButton.widthFor(ui, label), 14);
			chrome.add(update);
		}
	}

	private String updateButtonLabel() {
		return switch (Updater.state()) {
			case DOWNLOADING -> "Updating...";
			case READY -> "Restart to update";
			default -> "Update";
		};
	}

	/** Left edge of the top bar's version text (the update button sits there when shown). */
	private int versionX() {
		return px + 31 + ui.boldWidth("AutoDonut") + 6;
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
				if (Lockdown.active()) {
					if (Lockdown.active() && ServerContext.isOnDonut()) {
						if (bootStartedAt < 0) {
							widgets.add(new UiButton("Boot up", UiButton.Style.PRIMARY, () -> {
								bootStartedAt = System.currentTimeMillis();
								bootResults.clear();
								bootWaiting = false;
								rebuildPending = true;
							}).bounds(x + w / 2 - 40, top + 118, 80, 20));
						} else if (bootWaiting) {
							widgets.add(new UiButton("Continue anyway", UiButton.Style.PRIMARY, this::finishBoot)
									.bounds(x + w / 2 + 4, top + 196, 100, 18));
							widgets.add(new UiButton("Cancel", UiButton.Style.SECONDARY, () -> {
								bootStartedAt = -1;
								bootWaiting = false;
								rebuildPending = true;
							}).bounds(x + w / 2 - 70, top + 196, 66, 18));
						}
					}
					break;
				}
				AutoAuctionController auction = AutoAuctionController.get();
				List<FeatureCard> cards = List.of(
						new FeatureCard("emerald", "Auto Auction", "Lists matching items on /ah for you",
								auction::status, () -> cfg.autoAuctionEnabled, v -> {
									cfg.setAutoAuction(v);
									AutoDonutConfig.save();
								}, () -> setPage(Page.AUCTION)).locked(() -> runLocked() || !cfg.hasAuctionItems(), () -> runLocked() ? "Offline" : "No items"),
						new FeatureCard("gold_ingot", "Quick Sell", "Press its key on a held or hovered item to set a price and sell it",
								() -> "Key: " + AutoDonutClient.quickSellKeyName(), () -> cfg.quickSellEnabled, v -> {
									cfg.quickSellEnabled = v;
									AutoDonutConfig.save();
								}, null).locked(this::runLocked),
						new FeatureCard("spyglass", "HUD Status", "Small status label while Auto Auction runs",
								() -> "", () -> cfg.showHud, v -> {
									cfg.showHud = v;
									AutoDonutConfig.save();
								}, null).locked(this::runLocked),
						new FeatureCard("gold_nugget", "Auto Buy", "Buys matching /ah listings within your budget",
								AutoBuyController.get()::status, () -> cfg.autoBuyEnabled, v -> {
									cfg.setAutoBuy(v);
									AutoDonutConfig.save();
								}, () -> setPage(Page.AUTOBUY)).locked(() -> runLocked() || !cfg.hasBuyItems(), () -> runLocked() ? "Offline" : "No items"),
						new FeatureCard("ender_eye", "Streamer Mode", "Hides AutoDonut from replay mods and recordings: status label, chat messages and sounds",
								() -> Compat.hasRecorder() ? "Recording mod found" : "", () -> Compat.streamerMode(), v -> {
									cfg.streamerMode = v;
									AutoDonutConfig.save();
								}, null)
				);
				int introH = introHeight(w);
				int cardsTop = top + introH + 22;
				// Rows: Auto Auction + Auto Buy, Streamer Mode (full width), then Quick Sell + HUD Status.
				// cards = [Auto Auction, Quick Sell, HUD Status, Auto Buy, Streamer Mode]
				List<List<FeatureCard>> layout = List.of(
						List.of(cards.get(0), cards.get(3)),
						List.of(cards.get(4)),
						List.of(cards.get(1), cards.get(2)));
				int gap = 8;
				int ch = Math.max(46, Math.min(72, (py + ph - 8 - cardsTop - gap * (layout.size() - 1)) / layout.size()));
				for (int r = 0; r < layout.size(); r++) {
					List<FeatureCard> row = layout.get(r);
					int cw = (w - gap * (row.size() - 1)) / row.size();
					for (int c = 0; c < row.size(); c++) {
						row.get(c).bounds(x + c * (cw + gap), cardsTop + r * (ch + gap), cw, ch);
						widgets.add(row.get(c));
					}
				}
			}
			case AUCTION -> {
				ToggleSwitch master = new ToggleSwitch(() -> cfg.autoAuctionEnabled && !runLocked() && cfg.hasAuctionItems(), v -> {
					cfg.setAutoAuction(v);
					AutoDonutConfig.save();
				}).disabled(() -> runLocked() || !cfg.hasAuctionItems());
				master.bounds(x + w - ToggleSwitch.WIDTH - 8, top + 8, ToggleSwitch.WIDTH, ToggleSwitch.HEIGHT);
				widgets.add(master);

				RuleGrid<AuctionRule> grid = new RuleGrid<>(cfg.rules, RuleGrid.AUCTION, this::editRule, this::addRule, rule -> {
					cfg.onRuleToggled(rule);
					AutoDonutConfig.save();
				}).locked(this::runLocked);
				grid.bounds(x, top + 50, w, py + ph - 8 - (top + 50));
				widgets.add(grid);
			}
			case AUTOBUY -> {
				ToggleSwitch master = new ToggleSwitch(() -> cfg.autoBuyEnabled && !runLocked() && cfg.hasBuyItems(), v -> {
					cfg.setAutoBuy(v);
					AutoDonutConfig.save();
				}).disabled(() -> runLocked() || !cfg.hasBuyItems());
				master.bounds(x + w - ToggleSwitch.WIDTH - 8, top + 8, ToggleSwitch.WIDTH, ToggleSwitch.HEIGHT);
				widgets.add(master);

				RuleGrid<BuyRule> grid = new RuleGrid<>(cfg.buyRules, RuleGrid.BUY, this::editBuyRule, this::addBuyRule, rule -> {
					cfg.onBuyRuleToggled(rule);
					AutoDonutConfig.save();
				}).locked(this::runLocked);
				grid.bounds(x, top + 50, w, py + ph - 8 - (top + 50));
				widgets.add(grid);
			}
			case SAFETY -> {
				widgets.add(new UiButton("Reset to defaults", UiButton.Style.SECONDARY, () -> {
					cfg.resetSafety();
					AutoDonutConfig.save();
					rebuildPending = true;
				}).bounds(x + w - 104, py + TOP + 10, 104, 18));
				List<SettingRow> rows = List.of(
						new SettingRow("Minimum delay", "",
								new Slider(1, 120, () -> cfg.minDelaySeconds, v -> {
									cfg.minDelaySeconds = v;
									if (cfg.maxDelaySeconds < v) cfg.maxDelaySeconds = v;
								}, v -> v + "s"), 110).warning(this::minDelayWarning),
						new SettingRow("Maximum delay", "",
								new Slider(5, 300, () -> cfg.maxDelaySeconds, v -> {
									cfg.maxDelaySeconds = v;
									if (cfg.minDelaySeconds > v) cfg.minDelaySeconds = Math.max(1, v);
								}, v -> v + "s"), 110).warning(this::maxDelayWarning),
						new SettingRow("Detection speed", "How quickly new items are noticed and listed",
								new Segmented(new String[]{"Relaxed", "Normal", "Fast"}, () -> cfg.detectionSpeed, i -> {
									cfg.detectionSpeed = i;
									AutoDonutConfig.save();
								}), 130).warning(() -> cfg.detectionSpeed == 2
										? "Fast reacts to new items and confirms in a fraction of a second. "
										+ "That's quicker than most people, so it's easier to spot. Use it with longer delays."
										: null),
						new SettingRow("Reaction time", "Max wait after picking up an item",
								new Slider(1, 15, () -> cfg.maxReactionSeconds, v -> cfg.maxReactionSeconds = v, v -> v + "s"), 110),
						new SettingRow("Random breaks", "Sometimes pause for a few minutes",
								toggle(() -> cfg.randomBreaks, v -> cfg.randomBreaks = v), ToggleSwitch.WIDTH),
						new SettingRow("Inventory items", "",
								new Segmented(new String[]{"Move to hotbar", "Hotbar only"}, () -> cfg.inventoryItems, i -> {
									cfg.inventoryItems = i;
									AutoDonutConfig.save();
								}), 210).info("Move to hotbar: a stack in your main inventory is first moved into a free hotbar "
										+ "slot (never the one you're holding) and listed from there. Hotbar only: only items already "
										+ "in your hotbar are listed; the rest of your inventory is left alone. Either way your hand "
										+ "and selected slot never change."),
						new SettingRow("Auto-confirm", "Click the confirm button after /ah sell",
								toggle(() -> cfg.autoConfirm, v -> cfg.autoConfirm = v), ToggleSwitch.WIDTH),
						new SettingRow("Confirm in background", "Don't show the confirm menu while clicking it",
								toggle(() -> cfg.confirmInBackground, v -> cfg.confirmInBackground = v), ToggleSwitch.WIDTH),
						new SettingRow("Server check", "Ping the server every 10s; pause and warn if it stops answering",
								toggle(() -> cfg.serverCheck, v -> cfg.serverCheck = v), ToggleSwitch.WIDTH),
						new SettingRow("Continue now key", "Press " + AutoDonutClient.skipKeyName() + " to reset every timer and paused item",
								toggle(() -> cfg.skipKeyEnabled, v -> cfg.skipKeyEnabled = v), ToggleSwitch.WIDTH),
						new SettingRow("Instant background confirm", "Answer hidden prompts at once so you're never blocked",
								toggle(() -> cfg.instantBackgroundConfirm, v -> cfg.instantBackgroundConfirm = v), ToggleSwitch.WIDTH),
						new SettingRow("Wait in AutoDonut panel", "Timers keep running; actions wait until you close it",
								toggle(() -> cfg.pauseInPanel, v -> cfg.pauseInPanel = v), ToggleSwitch.WIDTH),
						new SettingRow("Freeze in menus", "Freeze the current listing and timers while a chest, inventory or chat is open",
								toggle(() -> cfg.pauseInMenus, v -> cfg.pauseInMenus = v), ToggleSwitch.WIDTH),
						new SettingRow("HUD status", "Show what Auto Auction is doing",
								toggle(() -> cfg.showHud, v -> cfg.showHud = v), ToggleSwitch.WIDTH)
				);
				// Full-height rows (title + hint); the page scrolls when they don't all fit.
				safetyRows = rows;
				safetyScroll.snap(0);
				safetyScrollTarget = 0;
				layoutSafetyRows(0);
				widgets.addAll(rows);
			}
			case APPEARANCE -> buildAppearance(x, w, top);
			case UPDATING -> buildUpdating(x, w, top);
			case CHANGELOG -> changelogScroll = 0;
			case EDIT -> buildEditor(x, w, top);
			case BUY_EDIT -> buildBuyEditor(x, w, top);
		}
	}

	private void buildUpdating(int x, int w, int top) {
		var state = Updater.state();
		String action = switch (state) {
			case AVAILABLE -> "Update to v" + Updater.latest().version();
			case DOWNLOADING, READY, CHECKING -> null;
			default -> "Check for updates";
		};
		if (action != null) {
			int bw = UiButton.widthFor(ui, action);
			widgets.add(new UiButton(action, state == Updater.State.AVAILABLE ? UiButton.Style.PRIMARY : UiButton.Style.SECONDARY, () -> {
				if (Updater.state() == Updater.State.AVAILABLE) {
					Updater.install();
				} else {
					Updater.check();
				}
			}).bounds(x + w - bw - 8, top + 12, bw, 18));
		}
		SettingRow check = new SettingRow("Check for updates", "Look for a new version every time the game starts",
				toggle(() -> cfg.checkUpdates, v -> cfg.checkUpdates = v), ToggleSwitch.WIDTH);
		check.bounds(x, top + 50, w, SAFETY_ROW_H);
		SettingRow auto = new SettingRow("Auto update", "Download new versions by itself; they apply on the next restart",
				toggle(() -> cfg.autoUpdate, v -> {
					cfg.autoUpdate = v;
					if (v && Updater.state() == Updater.State.AVAILABLE) Updater.install();
				}), ToggleSwitch.WIDTH);
		auto.bounds(x, top + 50 + SAFETY_ROW_H + SAFETY_GAP, w, SAFETY_ROW_H);
		widgets.add(check);
		widgets.add(auto);
		changelogTop = top + 50 + 2 * (SAFETY_ROW_H + SAFETY_GAP) + 16;
	}

	/** Wrapped changelog lines for the given releases, each under its own version heading. */
	private List<NoteLine> noteLines(List<Updater.Release> releases, int w) {
		List<NoteLine> out = new ArrayList<>();
		for (Updater.Release r : releases) {
			if (!out.isEmpty()) out.add(new NoteLine("", 0));
			boolean current = r.version().equals(AutoDonutClient.version());
			out.add(new NoteLine("v" + r.version(), current ? 4 : 1));
			String v = r.version();
			if (!r.date().isEmpty()) out.add(new NoteLine(r.date(), 2, v));
			String body = r.changelog();
			for (String raw : body.split("\n")) {
				String line = raw.strip();
				if (line.isEmpty() || line.matches("(?i)^[-*]\\s*bump version.*")) continue;
				if (line.startsWith("#")) line = line.replaceFirst("^#+\\s*", "");
				if (line.startsWith("- ") || line.startsWith("* ")) line = "\u2022 " + line.substring(2);
				line = line.replace("**", "").replace("`", "");
				for (String l : ui.wrap(line, w)) out.add(new NoteLine(l, 0, v));
			}
		}
		return out;
	}

	/** The installed version and every older one, newest first (all of them if the current isn't listed). */
	private List<Updater.Release> installedAndOlder() {
		List<Updater.Release> all = Updater.releases();
		List<Updater.Release> out = new ArrayList<>();
		for (Updater.Release r : all) {
			if (Updater.compare(r.version(), AutoDonutClient.version()) <= 0) out.add(r);
		}
		return out.isEmpty() ? all : out;
	}

	/** Releases newer than the installed version, oldest first. */
	private List<Updater.Release> upcoming() {
		List<Updater.Release> out = new ArrayList<>();
		for (Updater.Release r : Updater.releases()) {
			if (Updater.compare(r.version(), AutoDonutClient.version()) > 0) out.add(0, r);
		}
		return out;
	}

	private String minDelayWarning() {
		if (cfg.minDelaySeconds < 2) {
			return "Very short minimum delay. Listing every few seconds is much faster than a person "
					+ "would, and is the easiest pattern for staff or anti-cheat to notice. 2s or more is safer.";
		}
		return null;
	}

	private String maxDelayWarning() {
		if (cfg.maxDelaySeconds < 4) {
			return "Very short maximum delay. Every listing happens within " + cfg.maxDelaySeconds
					+ "s of the last one, which looks automated. 4s or more is safer.";
		}
		if (cfg.maxDelaySeconds - cfg.minDelaySeconds < 2) {
			return "Minimum and maximum are almost the same, so listings happen on a near-fixed rhythm. "
					+ "Leave at least 2s between them so the timing stays irregular.";
		}
		return null;
	}

	private ToggleSwitch toggle(java.util.function.BooleanSupplier get, java.util.function.Consumer<Boolean> set) {
		return new ToggleSwitch(get, v -> {
			set.accept(v);
			AutoDonutConfig.save();
		});
	}

	private void buildEditor(int x, int w, int top) {
		AuctionRule rule = editing;
		if (rule == null) return;
		searchField = new TextField("Add items, e.g. Diamond or #foods", "", 40, c -> true, text -> {
			resultScroll = 0;
			results = text.trim().startsWith("#") ? ItemIndex.searchTags(text, 60) : ItemIndex.search(text, 60);
		}).searchIcon();
		searchField.bounds(x, top + 9, w, 18);
		widgets.add(searchField);
		editorMoving.clear();
		editorMoving.put(searchField, top + 9);
		editorContentH = 148;
		fadeInWidget = null;
		limitRowWidget = null;
		limitRowBase = Integer.MAX_VALUE;
		limitCollapse = 0;

		int half = (w - 6) / 2;
		TextField price = new TextField("e.g. 1.5k or 250000", rule.priceText, 16, PriceFormat::isPriceChar, t -> rule.priceText = t).prefix("$ ");
		price.bounds(x, top + 62, half, 18);
		widgets.add(price);
		editorMoving.put(price, top + 62);

		QuantityMode[] modes = QuantityMode.values();
		String[] labels = new String[modes.length];
		for (int i = 0; i < modes.length; i++) labels[i] = modes[i].label();
		Segmented quantity = new Segmented(labels, () -> rule.mode.ordinal(), i -> {
			if (rule.mode != modes[i]) {
				rule.mode = modes[i];
				rebuildPending = true;
			}
		});
		quantity.bounds(x + half + 6, top + 62, w - half - 6, 18);
		widgets.add(quantity);
		editorMoving.put(quantity, top + 62);

		if (rule.mode == QuantityMode.EXACTLY) {
			TextField amount = new TextField("1 to 64", Integer.toString(rule.amount), 2, Character::isDigit, t -> {
				if (t.isEmpty()) return;
				rule.amount = Math.max(1, Math.min(64, Integer.parseInt(t)));
			}).prefix("Amount  ");
			amount.bounds(x, top + 88, w, 18);
			widgets.add(amount);
			editorMoving.put(amount, top + 88);
		} else {
			RangeSlider range = new RangeSlider(1, 64, () -> rule.min, v -> rule.min = v, () -> rule.max, v -> rule.max = v);
			range.bounds(x, top + 86, w, 26);
			widgets.add(range);
			editorMoving.put(range, top + 86);
		}

		int by = Math.max(top + 156, py + ph - 26);
		editorButtonsY = by;
		int doneW = 60;
		widgets.add(new UiButton("Done", UiButton.Style.PRIMARY, this::finishEditing).bounds(x + w - doneW, by, doneW, 18));
		widgets.add(new UiButton("Delete", UiButton.Style.DANGER, () -> {
			cfg.rules.remove(rule);
			AutoDonutConfig.save();
			editing = null;
			setPage(Page.AUCTION);
		}).bounds(x, by, 56, 18));
	}

	/** Set by a budget mode switch so the new budget input fades in after the rebuild. */
	private boolean pendingFadeIn;
	private static final int BUY_ROWS_TOP = 122;

	private void buildBuyEditor(int x, int w, int top) {
		BuyRule rule = buyEditing;
		if (rule == null) return;
		searchField = new TextField("Add items, e.g. Diamond or #foods", "", 40, c -> true, text -> {
			resultScroll = 0;
			results = text.trim().startsWith("#") ? ItemIndex.searchTags(text, 60) : ItemIndex.search(text, 60);
		}).searchIcon();
		searchField.bounds(x, top + 9, w, 18);
		widgets.add(searchField);
		editorMoving.clear();
		editorMoving.put(searchField, top + 9);

		int half = (w - 6) / 2;
		BuyRule.BudgetMode[] budgetModes = BuyRule.BudgetMode.values();
		String[] budgetLabels = new String[budgetModes.length];
		for (int i = 0; i < budgetModes.length; i++) budgetLabels[i] = budgetModes[i].label();
		Segmented budget = new Segmented(budgetLabels, () -> rule.budgetMode.ordinal(), i -> {
			if (rule.budgetMode != budgetModes[i]) {
				rule.budgetMode = budgetModes[i];
				pendingFadeIn = true;
				rebuildPending = true;
			}
		}).tooltips("Exact: type a price. \"1k\" buys listings of exactly $1K, \"<1k\" below it, \">500\" above $500 (also <= and >=).",
				"Max: buys any matching listing priced at or below the slider's amount.");
		budget.bounds(x, top + 62, half, 18);
		widgets.add(budget);
		editorMoving.put(budget, top + 62);

		BuyRule.SearchMode[] searchModes = BuyRule.SearchMode.values();
		String[] searchLabels = new String[searchModes.length];
		for (int i = 0; i < searchModes.length; i++) searchLabels[i] = searchModes[i].label();
		Segmented search = new Segmented(searchLabels, () -> rule.searchMode.ordinal(), i -> rule.searchMode = searchModes[i])
				.tooltips("Opens /ah with the item's name so only matching listings show. Fastest; needs a plain item (not a #tag) to search for.",
						"Opens /ah and flips through the pages looking for matches. Works with #tags, but slower and more clicks.");
		search.bounds(x + half + 6, top + 62, w - half - 6, 18);
		widgets.add(search);
		editorMoving.put(search, top + 62);

		Widget budgetInput;
		if (rule.budgetMode == BuyRule.BudgetMode.EXACT) {
			budgetInput = new TextField("e.g. 1k, <1k or >500", rule.budgetExact, 16, BuyRule::isBudgetChar, t -> rule.budgetExact = t)
					.prefix("Price  ");
			budgetInput.bounds(x, top + 86, w, 18);
			editorMoving.put(budgetInput, top + 86);
		} else {
			long[] steps = BuyRule.MAX_STEPS;
			budgetInput = new Slider(0, steps.length - 1, rule::maxStepIndex, i -> rule.budgetMax = steps[i],
					i -> "$" + PriceFormat.format(steps[i])).labelWidth(44);
			budgetInput.bounds(x, top + 88, w, 14);
			editorMoving.put(budgetInput, top + 88);
		}
		widgets.add(budgetInput);
		fadeInWidget = budgetInput;
		if (pendingFadeIn) {
			pendingFadeIn = false;
			fadeIn.snap(0);
			fadeIn.set(1);
		} else {
			fadeIn.snap(1);
		}

		List<SettingRow> rows = List.of(
				new SettingRow("Minimum wait", "After a purchase, before checking again",
						new Slider(0, 60, () -> rule.delayMin, v -> {
							rule.delayMin = v;
							if (rule.delayMax < v) rule.delayMax = v;
						}, v -> v + "s"), 130).warning(() -> buyMinWaitWarning(rule)),
				new SettingRow("Maximum wait", "Each wait is random between the two",
						new Slider(0, 60, () -> rule.delayMax, v -> {
							rule.delayMax = v;
							if (rule.delayMin > v) rule.delayMin = v;
						}, v -> v + "s"), 130).warning(() -> buyMaxWaitWarning(rule)),
				new SettingRow("Pause after purchases", "Stop buying this item after a number of buys",
						new ToggleSwitch(() -> rule.pauseAfterEnabled, v -> rule.pauseAfterEnabled = v), ToggleSwitch.WIDTH),
				new SettingRow("Purchase limit", "Buys before it pauses (" + AutoDonutClient.buyPauseKeyName() + " resumes)",
						new Slider(1, 64, () -> rule.pauseAfterCount, v -> rule.pauseAfterCount = v, v -> Integer.toString(v)), 130),
				new SettingRow("Pause key", "Pressing " + AutoDonutClient.buyPauseKeyName() + " pauses and resumes this item",
						new ToggleSwitch(() -> rule.pauseKeyEnabled, v -> rule.pauseKeyEnabled = v), ToggleSwitch.WIDTH),
				new SettingRow("Speed", "How often /ah is checked",
						new Segmented(speedLabels(), () -> rule.speed.ordinal(), i -> rule.speed = BuyRule.Speed.values()[i])
								.tooltips("Checks every 15–30s.", "Checks every 5–10s.", "Checks every 2–5s.", "Checks every 1–2s.",
										"Checks every 0.5–1s."), speedControlWidth(w))
						.warning(() -> switch (rule.speed) {
							case FAST -> "Checks every 1–2s. Much faster than a person; more noticeable to staff.";
							case AGGRESSIVE -> "Checks more than once a second. No person can do that, so staff and anti-cheat "
									+ "can spot it easily. Only use it briefly, if at all.";
							default -> null;
						})
		);
		for (int i = 0; i < rows.size(); i++) {
			int ry = top + BUY_ROWS_TOP + i * (SAFETY_ROW_H + SAFETY_GAP);
			SettingRow row = rows.get(i);
			row.bounds(x, ry, w, SAFETY_ROW_H);
			widgets.add(row);
			editorMoving.put(row, ry);
			if (i == 3) {
				// "Purchase limit" only shows while "Pause after purchases" is on.
				limitRowWidget = row;
				limitRowBase = ry;
			}
		}
		editorContentH = BUY_ROWS_TOP + rows.size() * (SAFETY_ROW_H + SAFETY_GAP);

		int by = Math.max(top + 156, py + ph - 26);
		editorButtonsY = by;
		int doneW = 60;
		widgets.add(new UiButton("Done", UiButton.Style.PRIMARY, () -> setPage(Page.AUTOBUY)).bounds(x + w - doneW, by, doneW, 18));
		widgets.add(new UiButton("Delete", UiButton.Style.DANGER, () -> {
			cfg.buyRules.remove(rule);
			AutoDonutConfig.save();
			buyEditing = null;
			setPage(Page.AUTOBUY);
		}).bounds(x, by, 56, 18));
	}

	private static String buyMinWaitWarning(BuyRule rule) {
		if (rule.delayMin < 2) {
			return "Very short wait after a purchase. Buying again within a second or two looks automated; 2s or more is safer.";
		}
		return null;
	}

	private static String buyMaxWaitWarning(BuyRule rule) {
		if (rule.delayMax < 4) {
			return "Very short maximum wait. Every purchase follows the last within " + rule.delayMax
					+ "s, which looks automated. 4s or more is safer.";
		}
		if (rule.delayMax - rule.delayMin < 2) {
			return "Minimum and maximum are almost the same, so purchases happen on a fixed rhythm. Leave at least 2s between them.";
		}
		return null;
	}

	/** Wide enough for every speed label (Aggressive included), leaving room for the row's title. */
	private int speedControlWidth(int rowW) {
		int total = 0;
		for (String l : speedLabels()) total += ui.width(l) + 14;
		return Math.min(rowW - 110, Math.max(250, total));
	}

	private static String[] speedLabels() {
		BuyRule.Speed[] speeds = BuyRule.Speed.values();
		String[] labels = new String[speeds.length];
		for (int i = 0; i < speeds.length; i++) labels[i] = speeds[i].label();
		return labels;
	}

	/** Labels and the budget summary line of the buy editor (moves with the chips and scrolling). */
	private void drawBuyEditorDecor(int x, int top, int w) {
		Theme t = ui.theme;
		BuyRule rule = buyEditing;
		if (rule == null) return;
		int s0 = -editScroll;
		int shift = editExtra + s0;
		ui.text("Items", x, top + s0, t.textMuted());
		drawChips(rule.items, x, top + 30 + s0, w);
		int labelY = top + 53 + shift;
		if (!underResults(labelY, labelY + 9)) {
			ui.text("Budget", x, labelY, t.textMuted());
			ui.text("Search mode", x + (w - 6) / 2 + 6, labelY, t.textMuted());
		}

		String summary;
		int color = t.textMuted();
		if (!rule.budgetValid()) {
			summary = "Enter a price, e.g. 1k (exactly), <1k (below) or >500 (above)";
			color = t.danger();
		} else {
			summary = "Budget: " + rule.budgetText() + " for the whole listing";
		}
		if (rule.searchMode == BuyRule.SearchMode.SEARCH && rule.items.stream().anyMatch(e -> e.startsWith("#"))) {
			summary += "  -  #tags use Browse";
		}
		int summaryY = top + 108 + shift;
		if (!underResults(summaryY, summaryY + 9)) ui.text(ui.trim(summary, w), x, summaryY, color);
	}

	private void buildAppearance(int x, int w, int top) {
		widgets.add(new SettingRow("Mode", "Dark or light surfaces",
				new Segmented(new String[]{"Dark", "Light"}, () -> cfg.darkMode ? 0 : 1, i -> {
					cfg.darkMode = i == 0;
					AutoDonutConfig.save();
					rebuildPending = true;
				}), 110).bounds(x, top, w, 24));

		int y = top + 42;
		List<Theme.Base> bases = cfg.darkMode ? Theme.DARK_BASES : Theme.LIGHT_BASES;
		ChoiceGrid styles = new ChoiceGrid(bases.size(), bases.size(), 40,
				() -> bases.indexOf(Theme.findBase(bases, cfg.darkMode ? cfg.darkStyle : cfg.lightStyle)),
				i -> {
					if (cfg.darkMode) cfg.darkStyle = bases.get(i).name();
					else cfg.lightStyle = bases.get(i).name();
					AutoDonutConfig.save();
				},
				(ui, i, tx, ty, tw, th, selected) -> {
					Theme.Base b = bases.get(i);
					// Miniature window: sidebar, panel and a text line in that style's colours
					int mx = tx + 5, my = ty + 5, mw = tw - 10, mh = 18;
					ui.round(mx, my, mw, mh, 2, b.panel());
					ui.fill(mx, my + 2, mx + mw / 4, my + mh - 2, b.sidebar());
					ui.fill(mx + mw / 4 + 3, my + 4, mx + mw - 4, my + 6, b.text());
					ui.fill(mx + mw / 4 + 3, my + 8, mx + mw - 10, my + 10, b.textMuted());
					ui.fill(mx + mw / 4 + 3, my + 12, mx + mw / 4 + 12, my + 14, ui.theme.accent());
					ui.text(ui.trim(b.name(), tw - 10), tx + 5, ty + th - 13, selected ? ui.theme.text() : ui.theme.textMuted());
				});
		styles.bounds(x, y, w, styles.h);
		widgets.add(styles);

		int ay = y + styles.h + 22;
		List<Theme.Accent> accents = Theme.ACCENTS;
		ChoiceGrid accentGrid = new ChoiceGrid(accents.size(), 4, 22,
				() -> accents.indexOf(Theme.findAccent(cfg.accent)),
				i -> {
					cfg.accent = accents.get(i).name();
					AutoDonutConfig.save();
				},
				(ui, i, tx, ty, tw, th, selected) -> {
					Theme.Accent a = accents.get(i);
					ui.round(tx + 5, ty + 5, 12, 12, 3, a.color());
					if (selected) ui.fill(tx + 9, ty + 9, tx + 13, ty + 13, 0xFFFFFFFF);
					ui.text(ui.trim(a.name(), tw - 28), tx + 23, ty + 8, selected ? ui.theme.text() : ui.theme.textMuted());
				});
		accentGrid.bounds(x, ay, w, accentGrid.h);
		widgets.add(accentGrid);

		widgets.add(new SettingRow("Status label position", "Nudge it if it overlaps your minimap",
				new Slider(-60, 200, () -> cfg.hudOffset, v -> {
					cfg.hudOffset = v;
					AutoDonutConfig.save();
				}, v -> v == 0 ? "Auto" : (v > 0 ? "+" : "") + v), 130).bounds(x, ay + accentGrid.h + 10, w, 24));
		appearanceLabels = new int[]{y - 11, ay - 11};
	}

	private void setPage(Page next) {
		if (page == Page.EDIT && next != Page.EDIT) cleanUpEditing();
		if (page == Page.BUY_EDIT && next != Page.BUY_EDIT) cleanUpBuyEditing();
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
		editorScroll.snap(0);
		editorScrollTarget = 0;
		chipsExtra.snap((chipLines(rule.items, contentX(), contentW()) - 1) * CHIP_LINE);
		customRow.snap(rule.mode == QuantityMode.CUSTOM ? 1 : 0);
		setPage(Page.EDIT);
	}

	private void addBuyRule() {
		BuyRule rule = new BuyRule();
		cfg.buyRules.add(rule);
		editBuyRule(rule);
		if (searchField != null) searchField.setFocused(true);
	}

	private void editBuyRule(BuyRule rule) {
		buyEditing = rule;
		editorScroll.snap(0);
		editorScrollTarget = 0;
		chipsExtra.snap((chipLines(rule.items, contentX(), contentW()) - 1) * CHIP_LINE);
		limitRow.snap(rule.pauseAfterEnabled ? 1 : 0);
		setPage(Page.BUY_EDIT);
	}

	/** Buy items left without an item are dropped; everything else is kept and saved. */
	private void cleanUpBuyEditing() {
		if (buyEditing != null && !buyEditing.hasItems()) cfg.buyRules.remove(buyEditing);
		buyEditing = null;
		AutoDonutConfig.save();
	}

	/** Whether an item editor (Auto Auction or Auto Buy) is the open page. */
	private boolean editorPage() {
		return page == Page.EDIT || page == Page.BUY_EDIT;
	}

	/** Item entries of the rule being edited, or null when no editor is open. */
	private List<String> editItems() {
		if (page == Page.EDIT && editing != null) return editing.items;
		if (page == Page.BUY_EDIT && buyEditing != null) return buyEditing.items;
		return null;
	}

	private void finishEditing() {
		setPage(Page.AUCTION);
	}

	/** Rules left without an item are dropped; everything else is kept and saved. */
	private void cleanUpEditing() {
		if (editing != null && !editing.hasItems()) cfg.rules.remove(editing);
		editing = null;
		AutoDonutConfig.save();
	}

	/** Whether the open item results list covers any of the rows from y1 to y2. */
	private boolean underResults(int y1, int y2) {
		if (!resultsVisible()) return false;
		int top = searchField.y + searchField.h + 2;
		int bottom = top + Math.min(MAX_RESULTS_SHOWN, results.size()) * RESULT_ROW_H + 4;
		return y2 > top && y1 < bottom;
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
		Theme target = Theme.current(cfg);
		if (!target.equals(themeTo)) {
			themeFrom = ui.theme;
			themeTo = target;
			themeAnim.snap(0);
			themeAnim.set(1);
		}
		ui.theme = Theme.lerp(themeFrom, themeTo, Anim.easeInOut(themeAnim.update(ui.dt)));
		ui.font = font;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		updateFrame();
		ui.g = graphics;
		ui.alpha = openProgress;
		ui.fill(0, 0, width, height, ui.theme.scrim());
	}

	/** Keeps the hidden vanilla text box focused whenever one of our text fields is. */
	private void syncInputFocus() {
		if (inputSink == null) return;
		boolean typing = anyFocused();
		if (typing && getFocused() != inputSink) {
			setFocused(inputSink);
			inputSink.setFocused(true);
		} else if (!typing && getFocused() == inputSink) {
			inputSink.setFocused(false);
			setFocused(null);
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		boolean offlineNow = offlineReason() != null;
		if (offlineNow != lastOffline) {
			lastOffline = offlineNow;
			if (page == Page.HOME) rebuildPending = true;
		}
		Updater.State us = Updater.state();
		if (us != lastUpdaterState) {
			lastUpdaterState = us;
			buildChrome();
			if (page == Page.UPDATING) rebuildPending = true;
		}
		if (rebuildPending) {
			rebuildPending = false;
			buildPage();
		}
		syncInputFocus();
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

		lastMouseX = mouseX;
		lastMouseY = mouseY;
		drawPanel(mouseX, mouseY);

		float pe = Anim.easeOutCubic(pageAnim.update(ui.dt));
		ui.alpha = openProgress * pe;
		graphics.pose().pushMatrix();
		graphics.pose().translate((1f - pe) * 10f, 0);
		boolean editorClip = editorPage() && editItems() != null;
		if (editorClip) {
			layoutEditor();
			ui.scissor(contentX() - 2, bodyTop() - 2, contentX() + contentW() + 2, editorButtonsY - 4);
		}
		drawPageDecor();
		if (editorClip) ui.endScissor();
		float pageAlpha = ui.alpha;
		boolean safetyClip = page == Page.SAFETY && safetyMaxScroll() > 0;
		if (page == Page.SAFETY) {
			safetyScrollTarget = Math.max(0, Math.min(safetyMaxScroll(), safetyScrollTarget));
			safetyScroll.set(safetyScrollTarget);
			layoutSafetyRows(Math.round(safetyScroll.update(ui.dt)));
		}
		if (safetyClip) ui.scissor(contentX(), bodyTop(), contentX() + contentW(), py + ph - 8);
		for (Widget widget : widgets) {
			if (editorClip && editorMoving.containsKey(widget)) {
				ui.scissor(contentX() - 2, bodyTop() - 2, contentX() + contentW() + 2, editorButtonsY - 4);
				if (widget == limitRowWidget) {
					float f = Anim.easeInOut(limitRow.get());
					ui.alpha = pageAlpha * f;
					graphics.pose().pushMatrix();
					graphics.pose().translate(0, (1f - f) * -6f);
					widget.render(ui, mouseX, mouseY);
					graphics.pose().popMatrix();
					ui.alpha = pageAlpha;
				} else if (widget == fadeInWidget) {
					// Freshly swapped input (mode switch): fades in and settles into place.
					float f = Anim.easeInOut(fadeIn.update(ui.dt));
					ui.alpha = pageAlpha * f;
					graphics.pose().pushMatrix();
					graphics.pose().translate(0, (1f - f) * -6f);
					widget.render(ui, mouseX, mouseY);
					graphics.pose().popMatrix();
					ui.alpha = pageAlpha;
				} else {
					widget.render(ui, mouseX, mouseY);
				}
				ui.endScissor();
				continue;
			}
			if (widget instanceof RangeSlider) {
				ui.alpha = pageAlpha * Anim.easeInOut(customRow.get());
				graphics.pose().pushMatrix();
				graphics.pose().translate(0, (1f - customRow.get()) * -6f);
				widget.render(ui, mouseX, mouseY);
				graphics.pose().popMatrix();
				ui.alpha = pageAlpha;
			} else if (revealAt >= 0 && page == Page.HOME) {
				// Post-boot reveal: each card rises and fades in, one after another.
				int index = widgets.indexOf(widget);
				float r = Anim.easeOutCubic(Anim.clamp01((System.currentTimeMillis() - revealAt - 150 - index * 90) / 380f));
				ui.alpha = pageAlpha * r;
				graphics.pose().pushMatrix();
				graphics.pose().translate(0, (1f - r) * 12f);
				widget.render(ui, mouseX, mouseY);
				graphics.pose().popMatrix();
				ui.alpha = pageAlpha;
			} else {
				widget.render(ui, mouseX, mouseY);
			}
		}
		if (revealAt >= 0 && System.currentTimeMillis() - revealAt > 1500) revealAt = -1;
		if (editorClip && editorMaxScroll() > 0) {
			int track = editorButtonsY - 4 - bodyTop();
			int content = track + editorMaxScroll();
			int barH = Math.max(16, track * track / content);
			int barY = bodyTop() + Math.round((track - barH) * (editScroll / (float) editorMaxScroll()));
			ui.fill(contentX() + contentW() + 3, barY, contentX() + contentW() + 5, barY + barH, ui.theme.track());
		}
		if (safetyClip) {
			ui.endScissor();
			int track = py + ph - 8 - bodyTop();
			int content = safetyRows.size() * (SAFETY_ROW_H + SAFETY_GAP) - SAFETY_GAP;
			int barH = Math.max(16, track * track / content);
			int barY = bodyTop() + Math.round((track - barH) * (safetyScroll.get() / safetyMaxScroll()));
			ui.fill(contentX() + contentW() - 2, barY, contentX() + contentW(), barY + barH, ui.theme.track());
		}
		if (resultsVisible()) drawResults(mouseX, mouseY);
		ui.alpha = openProgress;
		ui.drawTooltip(px + 4, py + 4, px + pw - 4, py + ph - 4);
		graphics.pose().popMatrix();

		graphics.pose().popMatrix();
	}

	private int activeNavIndex() {
		Page target = page == Page.EDIT ? Page.AUCTION : page == Page.BUY_EDIT ? Page.AUTOBUY : page;
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
		int vx = versionX();
		if (Updater.updateAvailable()) vx += UiButton.widthFor(ui, updateButtonLabel()) + 4;
		String ver = "v" + AutoDonutClient.version();
		boolean overVer = mx >= vx && mx < vx + ui.width(ver) + 8 && my >= by - 6 && my < by + 6;
		ui.round(vx, by - 6, ui.width(ver) + 8, 12, 3, overVer ? t.surfaceHover() : t.surface());
		ui.text(ver, vx + 4, by - 3, overVer ? t.text() : t.textMuted());
		boolean online = offlineReason() == null;
		String state = online ? "Online  -  Connected to Donut SMP" : "Offline";
		int sx = vx + ui.width(ver) + 14;
		ui.circle(sx + 3, by, 2, online ? t.success() : t.danger());
		ui.text(state, sx + 9, by - 3, online ? t.success() : t.danger());

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
				if (overHeader && navHover[i].target() < 0.5f) UiSounds.hover();
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
			if (hovered && navHover[i].target() < 0.5f) UiSounds.hover();
			navHover[i].set(hovered ? 1 : 0);
			navHover[i].update(ui.dt);
			int color = i == active ? t.text() : Anim.lerpColor(t.textMuted(), t.text(), navHover[i].get());
			ui.text(entry.label(), px + 14 + Math.round(navHover[i].get() * 2), iy + 5, color);
			if (entry.page() == Page.AUCTION) {
				auctionLight.anim.update(ui.dt);
				ui.circle(px + sw - 14, iy + 9, 2, auctionLight.color(AutoAuctionController.get().isActive()));
			} else if (entry.page() == Page.AUTOBUY) {
				buyLight.anim.update(ui.dt);
				ui.circle(px + sw - 14, iy + 9, 2, buyLight.color(AutoBuyController.get().isActive()));
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
		if (overClose && closeHover.target() < 0.5f) UiSounds.hover();
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
			case AUTOBUY -> "Auto Buy";
			case SAFETY -> "Safety";
			case APPEARANCE -> "Appearance";
			case UPDATING -> "Updating";
			case CHANGELOG -> "Changelog";
			case EDIT -> "Edit Item";
			case BUY_EDIT -> "Edit Buy Item";
		};
		String subtitle = switch (page) {
			case HOME -> "Your Donut SMP companion.";
			case AUCTION -> "Pick items to sell. Matching stacks are listed on /ah automatically.";
			case AUTOBUY -> "Pick items to buy. Listings within your budget are bought from /ah.";
			case SAFETY -> "Pacing that keeps every action irregular and human.";
			case APPEARANCE -> "Pick a colour style and an accent. Changes fade in instantly.";
			case UPDATING -> "Keep AutoDonut up to date and see what the next version brings.";
			case CHANGELOG -> "What changed in your version and every one before it.";
			case EDIT -> "Choose the item, its price and which stack sizes to sell.";
			case BUY_EDIT -> "Choose the items, your budget and how Auto Buy looks for them.";
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
				ui.text("Auto Auction", x + 8, top + 7, cfg.autoAuctionEnabled ? t.accent() : t.text());
				String tag = active ? "Running" : cfg.autoAuctionEnabled ? "Waiting" : "Off";
				int tagColor = active ? t.success() : cfg.autoAuctionEnabled ? t.textMuted() : t.textMuted();
				ui.text(tag, x + 14 + ui.width("Auto Auction"), top + 7, tagColor);
				// Status plus this hour's listings (recounted every frame, so it updates live).
				String hourText = auction.listedLastHour() + " this hour";
				ui.text(ui.trim(auction.status() + "  -  " + hourText, w - 50), x + 8, top + 21, t.textMuted());
			}
			case AUTOBUY -> {
				AutoBuyController buy = AutoBuyController.get();
				boolean active = buy.isActive();
				ui.round(x, top, w, 42, 3, t.surface());
				if (cfg.autoBuyEnabled) ui.outline(x, top, w, 42, 1, t.accent());
				ui.text("Auto Buy", x + 8, top + 7, cfg.autoBuyEnabled ? t.accent() : t.text());
				String tag = buy.keyPaused() ? "Paused" : active ? "Running" : cfg.autoBuyEnabled ? "Waiting" : "Off";
				int tagColor = buy.keyPaused() ? Ui.WARNING : active ? t.success() : t.textMuted();
				ui.text(tag, x + 14 + ui.width("Auto Buy"), top + 7, tagColor);
				String bought = buy.boughtThisSession() + " bought  -  " + AutoDonutClient.buyPauseKeyName() + " pauses";
				ui.text(ui.trim(buy.status() + "  -  " + bought, w - 50), x + 8, top + 21, t.textMuted());
			}
			case BUY_EDIT -> drawBuyEditorDecor(x, top, w);
			case EDIT -> {
				AuctionRule rule = editing;
				if (rule == null) return;
				int s0 = -editScroll;
				ui.text("Items", x, top + s0, t.textMuted());
				drawChips(rule.items, x, top + 30 + s0, w);
				ui.text("Price", x, top + 53 + editExtra + s0, t.textMuted());
				ui.text("Quantity", x + (w - 6) / 2 + 6, top + 53 + editExtra + s0, t.textMuted());

				ItemIndex.Entry entry = rule.hasItems() ? ItemIndex.entryFor(rule.items.get(0)) : null;
				customRow.set(rule.mode == QuantityMode.CUSTOM ? 1 : 0);
				float cr = Anim.easeInOut(customRow.update(ui.dt));
				drawPreview(rule, entry, x, top + 114 + Math.round(cr * 8) + editExtra - editScroll, w);
			}
			case UPDATING -> drawUpdating(x, top, w);
			case CHANGELOG -> drawChangelog(x, top, w);
			case APPEARANCE -> {
				if (appearanceLabels.length == 2) {
					ui.text(cfg.darkMode ? "Dark style" : "Light style", x, appearanceLabels[0], t.textMuted());
					ui.text("Accent colour", x, appearanceLabels[1], t.textMuted());
				}
			}
			case HOME -> {
				String offline = offlineReason();
				if (Lockdown.active() && bootStartedAt >= 0) {
					drawBooting(x, top, w);
					return;
				}
				if (Lockdown.active()) {
					drawOffline(offline, x, top, w);
					return;
				}
				if (revealAt >= 0) drawRevealSweep(x, top, w);
				int ih = introHeight(w);
				ui.round(x, top, w, ih, 3, t.surface());
				ui.fill(x, top, x + 2, top + ih, offline != null ? t.danger() : t.accent());
				List<String> lines = ui.wrap(introText(), w - 20);
				for (int i = 0; i < lines.size(); i++) {
					ui.text(lines.get(i), x + 10, top + 7 + i * 10, offline != null || i == 0 ? t.text() : t.textMuted());
				}
				ui.text("Features", x, top + ih + 9, t.textMuted());
				ui.fill(x + ui.width("Features") + 6, top + ih + 13, x + w, top + ih + 14, t.border());
			}
			default -> { }
		}
	}

	/** The colour breathing between dim and full brightness (about 1.6s per cycle) for "active" lights. */
	private int pulse(int color) {
		double phase = (System.currentTimeMillis() % 1600) / 1600.0 * Math.PI * 2;
		float k = 0.35f + 0.65f * (float) (0.5 + 0.5 * Math.cos(phase));
		return Anim.lerpColor(Anim.lerpColor(color, ui.theme.sidebar(), 0.7f), color, k);
	}

	/** Sidebar light that fades from grey to green when its feature turns on, then pulses from full brightness. */
	private final class ActiveLight {
		final Anim anim;
		long since = -1;

		ActiveLight(boolean active) {
			anim = new Anim(active ? 1 : 0, 6);
		}

		int color(boolean active) {
			Theme t = ui.theme;
			anim.set(active ? 1 : 0);
			float a = anim.get();
			if (!active || a < 0.999f) {
				since = -1;
				return Anim.lerpColor(t.track(), t.success(), a);
			}
			if (since < 0) since = System.currentTimeMillis();
			double phase = ((System.currentTimeMillis() - since) % 1600) / 1600.0 * Math.PI * 2;
			float k = 0.35f + 0.65f * (float) (0.5 + 0.5 * Math.cos(phase));
			return Anim.lerpColor(Anim.lerpColor(t.success(), t.sidebar(), 0.7f), t.success(), k);
		}
	}

	private final ActiveLight auctionLight = new ActiveLight(AutoAuctionController.get().isActive());
	private final ActiveLight buyLight = new ActiveLight(AutoBuyController.get().isActive());

	private int safetyMaxScroll() {
		int content = safetyRows.size() * (SAFETY_ROW_H + SAFETY_GAP) - SAFETY_GAP;
		return Math.max(0, content - (py + ph - 8 - bodyTop()));
	}

	/** Positions the Safety rows for the current scroll offset; rows out of view are hidden. */
	private void layoutSafetyRows(int offset) {
		int top = bodyTop();
		int bottom = py + ph - 8;
		for (int i = 0; i < safetyRows.size(); i++) {
			SettingRow row = safetyRows.get(i);
			int ry = top + i * (SAFETY_ROW_H + SAFETY_GAP) - offset;
			row.bounds(contentX(), ry, contentW() - (safetyMaxScroll() > 0 ? 6 : 0), SAFETY_ROW_H);
			row.visible = ry + SAFETY_ROW_H > top && ry < bottom;
		}
	}

	/** Running features can't be switched on while offline. */
	private boolean runLocked() {
		return offlineReason() != null;
	}

	/** Editor widgets that move with the chip list and scrolling, with their unshifted y. */
	private final java.util.LinkedHashMap<Widget, Integer> editorMoving = new java.util.LinkedHashMap<>();
	private int editorButtonsY;
	/** Extra height of the chip list beyond one line (animated) and the editor scroll offset. */
	private final Anim chipsExtra = new Anim(0, 12);
	private final Anim editorScroll = new Anim(0, 18);
	private float editorScrollTarget;
	private int editExtra;
	private int editScroll;
	private static final int CHIP_LINE = 18;

	/** Number of lines the chips wrap onto at this width. */
	private int chipLines(List<String> items, int x, int w) {
		if (items.isEmpty()) return 1;
		int cx = x;
		int lines = 1;
		for (String item : items) {
			int cw = chipWidth(item);
			if (cx + cw > x + w && cx > x) {
				lines++;
				cx = x;
			}
			cx += cw + 4;
		}
		return lines;
	}

	private int chipWidth(String item) {
		ItemIndex.Entry e = ItemIndex.entryFor(item);
		String name = e == null ? item : e.name();
		return 14 + Math.min(90, ui.width(name)) + 14;
	}

	private int editorMaxScroll() {
		int contentBottom = bodyTop() + editorContentH + editExtra - limitCollapse;
		return Math.max(0, contentBottom - (editorButtonsY - 6));
	}

	/** Moves editor widgets for the current chip height and scroll; called every frame on the editor. */
	private void layoutEditor() {
		List<String> items = editItems();
		if (items == null) return;
		int top = bodyTop();
		int lines = chipLines(items, contentX(), contentW());
		chipsExtra.set((lines - 1) * CHIP_LINE);
		editExtra = Math.round(chipsExtra.update(ui.dt));
		if (page == Page.BUY_EDIT && buyEditing != null && limitRowWidget != null) {
			limitRow.set(buyEditing.pauseAfterEnabled ? 1 : 0);
			limitCollapse = Math.round((1f - Anim.easeInOut(limitRow.update(ui.dt))) * (SAFETY_ROW_H + SAFETY_GAP));
		} else {
			limitCollapse = 0;
		}
		editorScrollTarget = Math.max(0, Math.min(editorMaxScroll(), editorScrollTarget));
		editorScroll.set(editorScrollTarget);
		editScroll = Math.round(editorScroll.update(ui.dt));
		for (var e : editorMoving.entrySet()) {
			Widget wd = e.getKey();
			int base = e.getValue();
			int y = base + (base > top + 40 ? editExtra : 0) - editScroll - (base > limitRowBase ? limitCollapse : 0);
			wd.bounds(wd.x, y, wd.w, wd.h);
			wd.visible = y + wd.h > top - 2 && y < editorButtonsY - 4;
			if (wd == limitRowWidget && limitRow.get() < 0.05f) wd.visible = false;
			// Keep what's under an open results list out of the way so the list stays readable and clickable.
			if (wd != searchField && underResults(y, y + wd.h)) wd.visible = false;
		}
	}

	/** Clickable remove buttons of the item chips drawn this frame: {x, y, w, h, index}. */
	private final List<int[]> chipHits = new ArrayList<>();

	/** The rule's items as chips (icon, name, x). Overflow collapses into "+N more". */
	private void drawUpdating(int x, int top, int w) {
		Theme t = ui.theme;
		var u = Updater.state();
		ui.round(x, top, w, 42, 3, t.surface());
		if (Updater.updateAvailable()) ui.outline(x, top, w, 42, 1, t.accent());
		ui.text("AutoDonut v" + AutoDonutClient.version(), x + 8, top + 7, t.text());
		var latest = Updater.latest();
		String status = switch (u) {
			case IDLE -> "Not checked yet";
			case CHECKING -> "Checking for updates...";
			case UP_TO_DATE -> "You're on the latest version";
			case AVAILABLE -> "Version " + latest.version() + " is available";
			case DOWNLOADING -> "Downloading v" + latest.version() + "  " + Math.round(Updater.progress() * 100) + "%";
			case READY -> "v" + latest.version() + " is installed. Restart the game to use it";
			case FAILED -> Updater.error();
		};
		int sc = u == Updater.State.FAILED ? t.danger() : u == Updater.State.READY ? t.success() : t.textMuted();
		ui.text(ui.trim(status, w - 130), x + 8, top + 22, sc);
		if (u == Updater.State.DOWNLOADING) ui.meter(x + w - 108, top + 19, 100, Updater.progress(), t.accent());

		ui.text("Next update", x, changelogTop - 14, t.textMuted());
		List<Updater.Release> next = upcoming();
		if (next.isEmpty()) {
			String msg = u == Updater.State.CHECKING ? "Checking..." : u == Updater.State.FAILED ? "Couldn't load updates."
					: "No update waiting. You're on the newest version.";
			ui.text(msg, x, changelogTop + 2, t.textMuted());
			return;
		}
		drawNotes(noteLines(next, w - 16), x, changelogTop, w, py + ph - 8);
	}

	private void drawChangelog(int x, int top, int w) {
		Theme t = ui.theme;
		changelogTop = top;
		if (Updater.releases().isEmpty()) {
			var u = Updater.state();
			String msg = u == Updater.State.CHECKING ? "Loading the changelog..."
					: u == Updater.State.FAILED ? "Couldn't load the changelog. Try Check for updates in Updating."
					: "No versions loaded yet. Use Check for updates in Updating.";
			ui.text(ui.trim(msg, w), x, top + 4, t.textMuted());
			return;
		}
		drawNotes(noteLines(installedAndOlder(), w - 16), x, top, w, py + ph - 8);
	}

	/** Scrollable box of changelog lines (mouse wheel scrolls it). */
	private void drawNotes(List<NoteLine> lines, int x, int boxTop, int w, int bottom) {
		Theme t = ui.theme;
		if (boxTop >= bottom - 12) return;
		ChangelogScreen.tickFolds(ui.dt);
		ui.round(x, boxTop, w, bottom - boxTop, 3, t.surface());
		int lh = ui.lineHeight() + 2;
		// Collapsing versions shrink their lines, so heights are summed per frame.
		int[] heights = new int[lines.size()];
		int total = 0;
		for (int i = 0; i < lines.size(); i++) {
			NoteLine l = lines.get(i);
			heights[i] = l.ver() == null ? lh : Math.round(lh * ChangelogScreen.fold(l.ver()));
			total += heights[i];
		}
		int view = bottom - boxTop - 12;
		int maxScroll = Math.max(0, (total - view + lh - 1) / lh);
		changelogScroll = Math.max(0, Math.min(changelogScroll, maxScroll));
		ui.scissor(x, boxTop + 4, x + w, bottom - 4);
		noteHeadings.clear();
		noteLineH = lh;
		noteBottom = bottom;
		float baseAlpha = ui.alpha;
		int yy = boxTop + 6 - changelogScroll * lh;
		for (int i = 0; i < lines.size() && yy <= bottom - 8; i++) {
			NoteLine l = lines.get(i);
			int h = heights[i];
			if (yy + h < boxTop) {
				yy += h;
				continue;
			}
			if (l.kind() == 1 || l.kind() == 4) {
				String ver = l.text().substring(1);
				noteHeadings.put(ver, yy);
				boolean over = lastMouseY >= yy - 1 && lastMouseY < yy - 1 + lh && lastMouseX >= x && lastMouseX < x + w;
				ChangelogScreen.arrow(ui, x + 5, yy + 1, ChangelogScreen.fold(ver) > 0.5f, over ? t.text() : t.accent());
				ui.bold(l.text(), x + 12, yy, over ? t.accent() : t.text());
				if (l.kind() == 4) ui.text("(installed)", x + 18 + ui.boldWidth(l.text()), yy, t.textMuted());
			} else {
				float f = l.ver() == null ? 1f : ChangelogScreen.fold(l.ver());
				if (f > 0.05f) {
					ui.alpha = baseAlpha * f * f;
					ui.text(l.text(), l.kind() == 2 ? x + 12 : x + 8, yy, l.kind() == 2 ? t.textMuted() : t.text());
					ui.alpha = baseAlpha;
				}
			}
			yy += h;
		}
		ui.endScissor();
		if (maxScroll > 0) {
			int trackH = bottom - boxTop - 8;
			int thumbH = Math.max(10, trackH * view / Math.max(1, total));
			int thumbY = boxTop + 4 + (trackH - thumbH) * changelogScroll / maxScroll;
			ui.round(x + w - 4, thumbY, 2, thumbH, 1, t.border());
		}
	}

	private void drawChips(List<String> items, int x, int y, int w) {
		Theme t = ui.theme;
		chipHits.clear();
		if (items.isEmpty()) {
			ui.text("No items yet: search above, or type # for a tag like #foods", x + 2, y + 3, t.textMuted());
			return;
		}
		int cx = x;
		int baseY = y;
		for (int i = 0; i < items.size(); i++) {
			ItemIndex.Entry e = ItemIndex.entryFor(items.get(i));
			String name = e == null ? items.get(i) : e.name();
			int cw = chipWidth(items.get(i));
			if (cx + cw > x + w && cx > x) {
				// Wrap onto the next line; new lines slide in as the area grows.
				cx = x;
				y += CHIP_LINE;
			}
			if (y + 14 > baseY + CHIP_LINE + editExtra) break;
			boolean over = lastMouseX >= cx && lastMouseX < cx + cw && lastMouseY >= y && lastMouseY < y + 14;
			ui.round(cx, y, cw, 14, 3, over ? t.surfaceHover() : t.surface());
			ui.outline(cx, y, cw, 14, 1, items.get(i).startsWith("#") ? t.accent() : t.border());
			if (e != null && !e.stack().isEmpty()) {
				ui.g.pose().pushMatrix();
				ui.g.pose().translate(cx + 2, y + 1);
				ui.g.pose().scale(0.75f);
				ui.item(e.stack(), 0, 0);
				ui.g.pose().popMatrix();
			}
			ui.text(ui.trim(name, 90), cx + 15, y + 3, t.text());
			ui.text("x", cx + cw - 9, y + 3, over ? t.danger() : t.textMuted());
			chipHits.add(new int[]{cx, y, cw, 14, i});
			cx += cw + 4;
		}
	}

	/** Why AutoDonut is offline, or null when it's running normally. */
	private String offlineReason() {
		if (Lockdown.active()) return Lockdown.reason();
		if (cfg.onlyOnDonut && !ServerContext.isOnDonut()) {
			return "Not connected to Donut SMP. AutoDonut only runs there.";
		}
		return null;
	}

	/** Runs and draws the in-panel boot-up: same steps as on joining, one line at a time. */
	private void drawBooting(int x, int top, int w) {
		Theme t = ui.theme;
		long elapsed = System.currentTimeMillis() - bootStartedAt;
		int total = com.autodonut.client.BootSequence.stepCount();
		while (bootResults.size() < total && elapsed >= (bootResults.size() + 1) * BOOT_STEP_MS) {
			bootResults.add(com.autodonut.client.BootSequence.runStep(bootResults.size()));
			UiSounds.slide(bootResults.size() / (float) total);
		}
		boolean allDone = bootResults.size() == total;
		boolean failed = bootResults.stream().anyMatch(java.util.Objects::nonNull);

		int boxH = failed && allDone ? 220 : 150;
		ui.round(x, top, w, boxH, 3, t.surface());
		ui.outline(x, top, w, boxH, 1, failed ? t.danger() : t.accent());
		// Progress bar along the top edge
		float progress = Math.min(1f, elapsed / (float) (BOOT_STEP_MS * (total + 1)));
		ui.fill(x + 1, top + 1, x + 1 + Math.round((w - 2) * progress), top + 3, failed ? t.danger() : t.accent());

		String dots = ".".repeat((int) ((elapsed / 300) % 4));
		ui.textCentered("AutoDonut Booting Up" + (allDone ? "" : dots), x + w / 2, top + 14, t.text());
		for (int i = 0; i < bootResults.size(); i++) {
			var r = bootResults.get(i);
			float fade = Anim.clamp01((elapsed - (i + 1) * BOOT_STEP_MS) / 220f);
			float base = ui.alpha;
			ui.alpha = base * fade;
			String line = (r == null ? "\u2714 " : "\u2718 ") + com.autodonut.client.BootSequence.stepLabel(i);
			ui.textCentered(line, x + w / 2, top + 34 + i * 12 + Math.round((1 - fade) * 4), r == null ? t.textMuted() : t.danger());
			ui.alpha = base;
		}
		if (!allDone) return;

		if (!failed) {
			if (elapsed > BOOT_STEP_MS * (total + 1)) finishBoot();
			return;
		}
		// Explain the first failure and offer to continue.
		var f = bootResults.stream().filter(java.util.Objects::nonNull).findFirst().get();
		int ey = top + 34 + total * 12 + 8;
		ui.fill(x + 12, ey, x + w - 12, ey + 1, t.border());
		int line = 0;
		for (String l : ui.wrap("Why: " + f.reason(), w - 40)) {
			ui.text(l, x + 20, ey + 8 + line++ * 10, t.textMuted());
		}
		for (String l : ui.wrap("Likely cause: " + f.cause(), w - 40)) {
			ui.text(l, x + 20, ey + 8 + line++ * 10, Ui.WARNING);
		}
		if (!bootWaiting) {
			bootWaiting = true;
			rebuildPending = true;
		}
	}

	/** Ends the lockdown and plays the reveal animation. */
	private void finishBoot() {
		Lockdown.bootUp();
		bootStartedAt = -1;
		bootWaiting = false;
		revealAt = System.currentTimeMillis();
		UiSounds.open();
		rebuildPending = true;
	}

	/** A bright accent line sweeping down the content area right after boot-up. */
	private void drawRevealSweep(int x, int top, int w) {
		float t = (System.currentTimeMillis() - revealAt) / 650f;
		if (t >= 1f) return;
		int y = top + Math.round(Anim.easeOutCubic(t) * (py + ph - 10 - top));
		float base = ui.alpha;
		ui.alpha = base * (1f - t);
		ui.fill(x, y, x + w, y + 1, ui.theme.accent());
		ui.fill(x, y - 6, x + w, y, (ui.theme.accent() & 0x00FFFFFF) | 0x22000000);
		ui.alpha = base;
	}

	private void drawOffline(String reason, int x, int top, int w) {
		Theme t = ui.theme;
		boolean locked = Lockdown.active();
		ui.round(x, top, w, 150, 3, t.surface());
		ui.outline(x, top, w, 150, 1, locked ? t.danger() : t.border());
		ui.g.pose().pushMatrix();
		ui.g.pose().translate(x + w / 2f, top + 18);
		ui.g.pose().scale(2f);
		ui.textCentered("Offline", 0, 0, locked ? t.danger() : t.textMuted());
		ui.g.pose().popMatrix();
		ui.textCentered(locked ? "Safety lockdown" : "Waiting for Donut SMP", x + w / 2, top + 42, t.text());
		List<String> lines = ui.wrap(reason, w - 40);
		for (int i = 0; i < Math.min(5, lines.size()); i++) {
			ui.textCentered(lines.get(i), x + w / 2, top + 58 + i * 10, t.textMuted());
		}
		if (locked && !ServerContext.isOnDonut()) {
			ui.textCentered("Join Donut SMP to boot AutoDonut up again.", x + w / 2, top + 122, t.textMuted());
		} else if (locked) {
			ui.textCentered("Auto Auction and Quick Sell stay off until you boot up.", x + w / 2, top + 142 - 0, t.textMuted());
		}
	}

	private static final String INTRO = "AutoDonut is a client-side helper for Donut SMP. It handles repetitive jobs, "
			+ "like listing items on the auction house, quietly in the background with randomised, human-like timing. "
			+ "Nothing is installed on the server. Switch each feature on or off below, or open it from the sidebar to "
			+ "change its settings.";

	private String introText() {
		String offline = offlineReason();
		return offline != null
				? "Offline. " + offline + " You can still change every setting; features start once you're connected."
				: INTRO;
	}

	private int introHeight(int w) {
		return ui.wrap(introText(), w - 20).size() * 10 + 14;
	}

	private void drawPreview(AuctionRule rule, ItemIndex.Entry entry, int x, int y, int w) {
		String names = entry == null ? "" : entry.name() + (rule.items.size() > 1 ? " and " + (rule.items.size() - 1) + " more" : "");
		Theme t = ui.theme;
		ui.card(x, y, w, 26, 6, Anim.lerpColor(t.panel(), t.accent(), 0.08f), Anim.lerpColor(t.border(), t.accent(), 0.35f));
		String line1;
		String line2;
		if (entry == null) {
			line1 = "Search for an item above";
			line2 = "Then set the price and stack size";
		} else if (rule.price() <= 0) {
			line1 = "Enter a price, e.g. 500, 1.5k or 2m";
			line2 = "Sells " + names + " stacks of " + rule.quantityText().toLowerCase();
		} else {
			line1 = "Sells " + names + " stacks of " + rule.quantityText().toLowerCase();
			int example = rule.mode == QuantityMode.EXACTLY ? rule.amount : rule.max;
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
			if (hovered && hoveredResult != idx) {
				hoveredResult = idx;
				UiSounds.hover();
			}
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
				if (idx >= 0 && idx < results.size()) {
					UiSounds.click();
					selectItem(results.get(idx));
				}
				return true;
			}
		}

		// Changelog headings fold their version open or closed
		if ((page == Page.CHANGELOG || page == Page.UPDATING) && my >= changelogTop && my < noteBottom) {
			for (var e : noteHeadings.entrySet()) {
				if (my >= e.getValue() - 1 && my < e.getValue() - 1 + noteLineH) {
					if (!ChangelogScreen.COLLAPSED.remove(e.getKey())) ChangelogScreen.COLLAPSED.add(e.getKey());
					UiSounds.click();
					return true;
				}
			}
		}

		// Version text opens the changelog
		int vx = versionX();
		if (Updater.updateAvailable()) vx += UiButton.widthFor(ui, updateButtonLabel()) + 4;
		int by = py + TOP / 2;
		String ver = "v" + AutoDonutClient.version();
		if (mx >= vx && mx < vx + ui.width(ver) + 8 && my >= by - 6 && my < by + 6) {
			UiSounds.click();
			setPage(Page.CHANGELOG);
			return true;
		}

		// Close button
		int cx = closeX(), cy = closeY();
		if (mx >= cx && mx < cx + 16 && my >= cy && my < cy + 16) {
			UiSounds.click();
			onClose();
			return true;
		}
		// Navigation
		for (int i = 0; i < NAV.length; i++) {
			int iy = navY(i);
			if (NAV[i].page() == null) {
				if (mx >= px + 5 && mx < px + sw - 5 && my >= iy && my < iy + NAV_SECTION_H) {
					sectionOpen[i].set(sectionOpen[i].target() > 0.5f ? 0 : 1);
					UiSounds.click();
					return true;
				}
				continue;
			}
			if (sectionOpen[sectionOf(i)].target() < 0.5f) continue;
			if (mx >= px + 5 && mx < px + sw - 5 && my >= iy && my < iy + NAV_ITEM_H) {
				if (NAV[i].page() != page) {
					UiSounds.click();
					setPage(NAV[i].page());
				}
				return true;
			}
		}

		List<String> chipItems = editItems();
		if (chipItems != null) {
			for (int[] c : chipHits) {
				if (mx >= c[0] && mx < c[0] + c[2] && my >= c[1] && my < c[1] + c[3]) {
					if (c[4] < chipItems.size()) chipItems.remove(c[4]);
					UiSounds.click();
					AutoDonutConfig.save();
					return true;
				}
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
		List<String> items = editItems();
		if (items != null && !items.contains(entry.id())) items.add(entry.id());
		searchField.setText("");
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
		if (editorPage() && editorMaxScroll() > 0 && my >= bodyTop() && my < editorButtonsY) {
			editorScrollTarget = Math.max(0, Math.min(editorMaxScroll(), editorScrollTarget - (float) scrollY * 22));
			return true;
		}
		if ((page == Page.UPDATING || page == Page.CHANGELOG) && my >= changelogTop) {
			changelogScroll = Math.max(0, changelogScroll - (int) Math.signum(scrollY) * 2);
			return true;
		}
		if (page == Page.SAFETY && safetyMaxScroll() > 0 && my >= bodyTop() && my < py + ph - 8) {
			// The wheel scrolls the page here; sliders are still dragged with the mouse.
			safetyScrollTarget = Math.max(0, Math.min(safetyMaxScroll(), safetyScrollTarget - (float) scrollY * 22));
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
		UiSounds.close();
		AutoDonutConfig.save();
		closingAt = System.nanoTime();
	}

	@Override
	public void tick() {
		super.tick();
		if (closingAt >= 0 && (System.nanoTime() - closingAt) / 1_000_000f >= CLOSE_MS) {
			minecraft.gui.setScreen(parent);
		}
	}

	@Override
	public void removed() {
		super.removed();
		if (page == Page.EDIT) cleanUpEditing();
		if (page == Page.BUY_EDIT) cleanUpBuyEditing();
		AutoDonutConfig.save();
	}
}
