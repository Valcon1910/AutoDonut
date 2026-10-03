package com.autodonut.client;

import net.fabricmc.loader.api.FabricLoader;

import com.autodonut.client.config.AutoDonutConfig;

/** Detects other mods AutoDonut should make room for or hide from. */
public final class Compat {
	private static final String[] MINIMAPS = {"xaerominimap", "xaerominimapfair", "journeymap", "voxelmap", "minimap"};
	private static final String[] RECORDERS = {"replaymod", "flashback", "screenshare", "streamer"};

	private static Boolean minimap;
	private static Boolean recorder;

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

	/** A replay or screen-recording mod is installed. */
	public static boolean hasRecorder() {
		if (recorder == null) recorder = any(RECORDERS);
		return recorder;
	}

	/** Streamer mode: turned on by the user, or automatically when a recording mod is present. */
	public static boolean streamerMode() {
		return AutoDonutConfig.get().streamerMode || hasRecorder();
	}
}
