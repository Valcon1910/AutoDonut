package com.autodonut.client.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

import com.autodonut.AutoDonut;

/** All user settings, stored as JSON in {@code config/autodonut.json}. */
public class AutoDonutConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static AutoDonutConfig instance;

	// Appearance
	public boolean darkMode = true;
	public String darkStyle = "Midnight";
	public String lightStyle = "Daylight";
	public String accent = "Donut Pink";

	// Auto Auction
	public boolean autoAuctionEnabled = false;
	public List<AuctionRule> rules = new ArrayList<>();
	/** Command sent to list the held item, without the leading slash. {price} is replaced with the total price. */
	public String sellCommand = "ah sell {price}";

	// Safety
	public boolean onlyOnDonut = true;
	public int minDelaySeconds = 2;
	public int maxDelaySeconds = 6;
	public int maxReactionSeconds = 2;
	public int maxListingsPerHour = 20;
	public boolean randomBreaks = true;
	public boolean pauseInMenus = true;
	public boolean showHud = true;
	public boolean autoConfirm = true;
	public boolean confirmInBackground = true;
	/** Answer hidden confirm prompts right away (keeps the moment inputs are blocked tiny). */
	public boolean instantBackgroundConfirm = true;
	/** Freeze Auto Auction while the AutoDonut panel is open. */
	public boolean pauseInPanel = true;
	public boolean streamerMode = false;
	/** Quick Sell: pressing R opens Donut's own sell screen for the held item. */
	public boolean quickSellEnabled = true;
	/** Safety lockdown: everything stays off until the player boots AutoDonut up again. */
	public boolean lockdown = false;
	public String lockdownReason = "";
	/** Command sent by Quick Sell, without the slash. Donut then asks for the price itself. */
	public String quickSellCommand = "ah sell";
	/** 0 = Relaxed, 1 = Normal, 2 = Fast. Scales how quickly items are noticed and handled. */
	public int detectionSpeed = 1;
	/** Extra horizontal offset for the status label, added to the automatic position. */
	public int hudOffset = 0;
	/** Items outside the hotbar: 0 = move them to a free hotbar slot first, 1 = ignore them (hotbar only). */
	public int inventoryItems = 0;

	/** Multiplier for the wait between listings (Fast lists noticeably sooner). */
	public float listingDelayFactor() {
		return switch (detectionSpeed) {
			case 0 -> 1.3f;
			case 2 -> 0.4f;
			default -> 1f;
		};
	}

	/** Multiplier for reaction and handling delays. */
	public float speedFactor() {
		return switch (detectionSpeed) {
			case 0 -> 1.6f;
			case 2 -> 0.35f;
			default -> 1f;
		};
	}

	public static AutoDonutConfig get() {
		if (instance == null) load();
		return instance;
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("autodonut.json");
	}

	/** Puts every Safety setting back to its default value. */
	public void resetSafety() {
		AutoDonutConfig d = new AutoDonutConfig();
		minDelaySeconds = d.minDelaySeconds;
		maxDelaySeconds = d.maxDelaySeconds;
		maxReactionSeconds = d.maxReactionSeconds;
		maxListingsPerHour = d.maxListingsPerHour;
		randomBreaks = d.randomBreaks;
		pauseInMenus = d.pauseInMenus;
		showHud = d.showHud;
		autoConfirm = d.autoConfirm;
		confirmInBackground = d.confirmInBackground;
		instantBackgroundConfirm = d.instantBackgroundConfirm;
		pauseInPanel = d.pauseInPanel;
		detectionSpeed = d.detectionSpeed;
		inventoryItems = d.inventoryItems;
	}

	/** Whether at least one item is fully set up (item + price). */
	public boolean hasAuctionItems() {
		return rules.stream().anyMatch(AuctionRule::isComplete);
	}

	/** Master switch: turning Auto Auction on or off does the same to every item. */
	public void setAutoAuction(boolean on) {
		autoAuctionEnabled = on;
		for (AuctionRule r : rules) r.enabled = on;
	}

	/**
	 * Keeps the master switch in step with the items: enabling an item turns Auto Auction on
	 * (other items stay as they are); disabling the last enabled item turns it off.
	 */
	public void onRuleToggled(AuctionRule rule) {
		if (rule.enabled) {
			autoAuctionEnabled = true;
		} else if (rules.stream().noneMatch(r -> r.enabled)) {
			autoAuctionEnabled = false;
		}
	}

	/** Problem from the last load, or null if the file was fine. */
	public static String lastLoadProblem;

	public static void load() {
		lastLoadProblem = null;
		Path path = path();
		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path)) {
				instance = GSON.fromJson(reader, AutoDonutConfig.class);
			} catch (IOException | RuntimeException e) {
				AutoDonut.LOGGER.error("Failed to read {}, using defaults", path, e);
				lastLoadProblem = "autodonut.json couldn't be read (" + e.getClass().getSimpleName() + "). Defaults are being used.";
			}
		}
		if (instance == null) instance = new AutoDonutConfig();
		instance.sanitize();
	}

	/**
	 * Checks that the settings file can be read, without replacing the settings in use.
	 * Returns a problem description, or null when the file is fine (or doesn't exist yet).
	 */
	public static String check() {
		Path path = path();
		if (!Files.exists(path)) return null;
		try (Reader reader = Files.newBufferedReader(path)) {
			GSON.fromJson(reader, AutoDonutConfig.class);
			return null;
		} catch (IOException | RuntimeException e) {
			return "autodonut.json couldn't be read (" + e.getClass().getSimpleName() + "). Defaults are being used.";
		}
	}

	public static void save() {
		if (instance == null) return;
		Path path = path();
		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path)) {
				GSON.toJson(instance, writer);
			}
		} catch (IOException e) {
			AutoDonut.LOGGER.error("Failed to save {}", path, e);
		}
	}

	private void sanitize() {
		if (rules == null) rules = new ArrayList<>();
		rules.removeIf(r -> r == null);
		for (AuctionRule r : rules) r.sanitize();
		if (darkStyle == null) darkStyle = "Midnight";
		if (lightStyle == null) lightStyle = "Daylight";
		if (accent == null) accent = "Donut Pink";
		if (sellCommand == null || sellCommand.isBlank()) sellCommand = "ah sell {price}";
		if (quickSellCommand == null || quickSellCommand.isBlank()) quickSellCommand = "ah sell";
		minDelaySeconds = Math.clamp(minDelaySeconds, 1, 120);
		maxDelaySeconds = Math.clamp(maxDelaySeconds, minDelaySeconds, 300);
		maxReactionSeconds = Math.clamp(maxReactionSeconds, 1, 15);
		maxListingsPerHour = Math.clamp(maxListingsPerHour, 1, 60);
		detectionSpeed = Math.clamp(detectionSpeed, 0, 2);
		// AutoDonut only ever runs on Donut SMP.
		onlyOnDonut = true;
		hudOffset = Math.clamp(hudOffset, -60, 200);
	}
}
