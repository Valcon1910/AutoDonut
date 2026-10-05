package com.autodonut.client;

import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import com.autodonut.AutoDonut;
import com.autodonut.client.auction.AutoAuctionController;
import com.autodonut.client.config.AutoDonutConfig;

/**
 * Safety lockdown. When a message suggests staff or the anti-cheat may be checking on the
 * player, every automated feature switches off and stays off (even after restarting) until
 * the player boots AutoDonut up again from the panel.
 */
public final class Lockdown {
	/** Words in a server (system) message that trigger the lockdown. */
	private static final String[] SYSTEM_TRIGGERS = {
			"macro", "auto sell", "autosell", "auto-sell", "autoclick", "auto click", "anticheat", "anti-cheat",
			"anti cheat", "frozen", "you have been frozen", "screenshare", "screen share", "staff check",
			"afk check", "suspicious", "unfair advantage", "illegal mod", "hacked client"
	};
	/** Words in a player chat message that trigger it when the message also mentions you. */
	private static final String[] CHAT_TRIGGERS = {
			"macro", "bot", "auto sell", "autosell", "cheat", "hack", "are you there", "respond", "screenshare", "ss", "freeze"
	};

	private Lockdown() {
	}

	public static boolean active() {
		return AutoDonutConfig.get().lockdown;
	}

	public static String reason() {
		String r = AutoDonutConfig.get().lockdownReason;
		return r == null || r.isEmpty() ? "AutoDonut was shut down for safety." : r;
	}

	public static void trigger(String reason) {
		AutoDonutConfig cfg = AutoDonutConfig.get();
		if (cfg.lockdown) return;
		cfg.lockdown = true;
		cfg.lockdownReason = reason;
		AutoDonutConfig.save();
		AutoAuctionController.get().reset();
		com.autodonut.client.auction.AutoBuyController.get().reset();
		AutoDonut.LOGGER.warn("AutoDonut lockdown: {}", reason);
	}

	/** Clears the lockdown ("Boot up" button). */
	public static void bootUp() {
		AutoDonutConfig cfg = AutoDonutConfig.get();
		cfg.lockdown = false;
		cfg.lockdownReason = "";
		AutoDonutConfig.save();
	}

	/** Checks a server message (system message / action bar). */
	public static void onSystemMessage(Component message) {
		if (!ServerContext.isOnDonut()) return;
		String text = message.getString().toLowerCase(Locale.ROOT);
		// Only messages aimed at you (broadcasts about other players are ignored).
		Minecraft mc = Minecraft.getInstance();
		String name = mc.player == null ? "" : mc.player.getName().getString().toLowerCase(Locale.ROOT);
		boolean aboutYou = containsWord(text, "you") || containsWord(text, "your") || (!name.isEmpty() && text.contains(name));
		if (!aboutYou) return;
		for (String word : SYSTEM_TRIGGERS) {
			if (containsWord(text, word)) {
				trigger("Donut SMP sent a message that looks like a staff or anti-cheat check: \"" + shorten(message.getString()) + "\"");
				return;
			}
		}
	}

	/** Checks a player chat message; only counts if it mentions you by name. */
	public static void onChatMessage(Component message) {
		if (!ServerContext.isOnDonut()) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		String text = message.getString().toLowerCase(Locale.ROOT);
		String name = mc.player.getName().getString().toLowerCase(Locale.ROOT);
		if (!text.contains(name)) return;
		for (String word : CHAT_TRIGGERS) {
			if (containsWord(text, word)) {
				trigger("Someone mentioned you in chat about possible automation: \"" + shorten(message.getString()) + "\"");
				return;
			}
		}
	}

	private static boolean containsWord(String text, String word) {
		int i = text.indexOf(word);
		while (i >= 0) {
			boolean startOk = i == 0 || !Character.isLetter(text.charAt(i - 1));
			int end = i + word.length();
			boolean endOk = end >= text.length() || !Character.isLetter(text.charAt(end)) || word.length() > 4;
			if (startOk && endOk) return true;
			i = text.indexOf(word, i + 1);
		}
		return false;
	}

	private static String shorten(String s) {
		return s.length() > 90 ? s.substring(0, 87) + "..." : s;
	}
}
