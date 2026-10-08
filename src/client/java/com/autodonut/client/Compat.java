package com.autodonut.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.fabricmc.loader.api.FabricLoader;

import com.autodonut.client.config.AutoDonutConfig;

/** Detects other mods AutoDonut should make room for or hide from. */
public final class Compat {
	private static final String[] MINIMAPS = {"xaerominimap", "xaerominimapfair", "journeymap", "voxelmap", "minimap"};
	private static final String[] RECORDERS = {"replaymod", "flashback", "screenshare", "streamer"};

	private static Boolean minimap;
	private static Boolean recorder;
	private static Boolean jade;
	private static Boolean roundMinimap;
	private static long shapeCheckedAt;
	private static final Pattern XAERO_SHAPE = Pattern.compile("minimapShape:(\\d+)");

	private Compat() {
	}

	private static boolean any(String[] ids) {
		FabricLoader loader = FabricLoader.getInstance();
		for (String id : ids) {
			if (loader.isModLoaded(id)) return true;
		}
		return false;
	}

	/** A top-left minimap mod is installed, so the HUD label should sit further right. */
	public static boolean hasMinimap() {
		if (minimap == null) minimap = any(MINIMAPS);
		return minimap;
	}

	/**
	 * Whether the minimap is round. Read from Xaero's config (minimapShape: 1 = circle) and
	 * re-checked every few seconds so changing the shape in-game is picked up.
	 */
	public static boolean minimapIsRound() {
		long now = System.currentTimeMillis();
		if (roundMinimap == null || now - shapeCheckedAt > 5000) {
			shapeCheckedAt = now;
			roundMinimap = readXaeroShape();
		}
		return roundMinimap;
	}

	private static boolean readXaeroShape() {
		Path config = FabricLoader.getInstance().getConfigDir();
		Path[] candidates = {
				config.resolve("xaerominimap.txt"),
				config.resolve("xaero").resolve("minimap.txt"),
				config.resolve("xaero").resolve("minimap").resolve("config.txt"),
				config.resolve("xaero").resolve("minimap").resolve("profiles").resolve("default.txt"),
		};
		for (Path p : candidates) {
			try {
				if (!Files.isRegularFile(p)) continue;
				Matcher m = XAERO_SHAPE.matcher(Files.readString(p));
				if (m.find()) return m.group(1).equals("1");
			} catch (Exception ignored) {
				// Unreadable config: fall through to the default.
			}
		}
		return false;
	}

	/** Left edge for the status label so it clears the minimap (square maps are wider at the top). */
	public static int hudX() {
		int base = !hasMinimap() ? 6 : minimapIsRound() ? 82 : 96;
		return Math.max(2, base + AutoDonutConfig.get().hudOffset);
	}

	/** A replay or screen-recording mod is installed. */
	public static boolean hasRecorder() {
		if (recorder == null) recorder = any(RECORDERS);
		return recorder;
	}

	/** Streamer mode: turned on by the user, or automatically when a recording mod is present. */
	public static boolean streamerMode() {
		return AutoDonutConfig.get().streamerMode || hasRecorder();
	}

	/** Jade (the "what am I looking at" tooltip mod) is installed. */
	public static boolean hasJade() {
		if (jade == null) jade = FabricLoader.getInstance().isModLoaded("jade");
		return jade;
	}

	/** Whether the look-at HUD is turned on (auto: on when Jade is installed). */
	public static boolean lookHudEnabled() {
		Boolean v = AutoDonutConfig.get().lookHud;
		return v == null || v; // unset = on, with or without Jade
	}
}
