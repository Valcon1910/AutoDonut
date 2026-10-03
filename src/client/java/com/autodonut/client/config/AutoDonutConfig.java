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

	// Auto Auction
	public boolean autoAuctionEnabled = false;
	public List<AuctionRule> rules = new ArrayList<>();
	/** Command sent to list the held item, without the leading slash. {price} is replaced with the total price. */
	public String sellCommand = "ah sell {price}";

	// Safety
	public boolean onlyOnDonut = true;
	public int minDelaySeconds = 8;
	public int maxDelaySeconds = 25;
	public int maxReactionSeconds = 4;
	public int maxListingsPerHour = 20;
	public boolean randomBreaks = true;
	public boolean pauseInMenus = true;
	public boolean showHud = true;

	public static AutoDonutConfig get() {
		if (instance == null) load();
		return instance;
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("autodonut.json");
	}

	public static void load() {
		Path path = path();
		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path)) {
				instance = GSON.fromJson(reader, AutoDonutConfig.class);
			} catch (IOException | RuntimeException e) {
				AutoDonut.LOGGER.error("Failed to read {}, using defaults", path, e);
			}
		}
		if (instance == null) instance = new AutoDonutConfig();
		instance.sanitize();
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
		for (AuctionRule r : rules) {
			if (r.itemId == null) r.itemId = "";
			if (r.priceText == null) r.priceText = "";
			if (r.mode == null) r.mode = QuantityMode.EXACTLY;
			r.amount = Math.clamp(r.amount, 1, 64);
		}
		if (sellCommand == null || sellCommand.isBlank()) sellCommand = "ah sell {price}";
		minDelaySeconds = Math.clamp(minDelaySeconds, 3, 120);
		maxDelaySeconds = Math.clamp(maxDelaySeconds, minDelaySeconds, 300);
		maxReactionSeconds = Math.clamp(maxReactionSeconds, 1, 15);
		maxListingsPerHour = Math.clamp(maxListingsPerHour, 1, 60);
	}
}
