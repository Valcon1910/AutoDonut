package com.autodonut.client.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import com.autodonut.client.BootSequence;
import com.autodonut.client.Compat;
import com.autodonut.client.ServerContext;
import com.autodonut.client.config.AutoDonutConfig;

/**
 * In-game "AutoDonut Booting Up" overlay, shown after Donut moves you between its servers or
 * after a maintenance / restart / session message. Runs the same checks as joining; Auto
 * Auction waits until it's done.
 */
public final class BootOverlay {
	private static final long STEP_MS = 260;
	private static final long LINGER_MS = 1600;
	/** Phrases meaning a session just (re)started; restart countdown warnings are skipped. */
	private static final String[] SESSION_WORDS = {
			"maintenance", "restarted", "back online", "rebooted", "reconnected", "sending you to", "sent you to",
			"you were moved", "moved you", "connecting you to", "transferred", "welcome back", "server is back"
	};

	private static final Ui UI = new Ui();
	private static long startedAt = -1;
	private static String reason = "";
	private static final List<BootSequence.Failure> results = new ArrayList<>();
	private static long lastJoinBoot;

	private BootOverlay() {
	}

	/** True while the checks are still running (Auto Auction waits for this). */
	public static boolean booting() {
		return startedAt >= 0 && results.size() < BootSequence.stepCount();
	}

	public static void start(String why) {
		if (!ServerContext.isOnDonut() || Compat.streamerMode() || AutoDonutConfig.get().lockdown) return;
		if (booting()) return;
		startedAt = System.currentTimeMillis();
		reason = why;
		results.clear();
	}

	/** A new play session began (first join or a server switch behind Donut's proxy). */
	public static void onJoin() {
		long now = System.currentTimeMillis();
		// The very first join already showed the checks on the connect screen.
		if (now - lastJoinBoot > 20_000 && lastJoinBoot != 0) start("Reconnected to Donut SMP");
		lastJoinBoot = now;
	}

	public static void onDisconnect() {
		lastJoinBoot = 0;
	}

	public static void onSystemMessage(Component message) {
		String text = message.getString().toLowerCase(Locale.ROOT);
		// "Server restarting in 5 minutes" is a warning, not a new session.
		if (text.contains("restarting in") || text.contains("restart in")) return;
		// "...an area in maintenance, try again in a few minutes": the move failed, nothing restarted.
		if (text.contains("try again") || text.contains("in maintenance")) return;
		for (String w : SESSION_WORDS) {
			if (text.contains(w)) {
				start(Character.toUpperCase(w.charAt(0)) + w.substring(1));
				return;
			}
		}
	}

	public static void extract(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		if (startedAt < 0) return;
		long elapsed = System.currentTimeMillis() - startedAt;
		int total = BootSequence.stepCount();
		while (results.size() < total && elapsed >= (results.size() + 1) * STEP_MS) {
			results.add(BootSequence.runStep(results.size()));
		}
		long doneAt = (total + 1) * STEP_MS;
		if (elapsed > doneAt + LINGER_MS) {
			startedAt = -1;
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		UI.g = graphics;
		UI.font = mc.font;
		UI.theme = Theme.current(AutoDonutConfig.get());
		float in = Anim.easeOutCubic(Anim.clamp01(elapsed / 220f));
		float out = 1f - Anim.clamp01((elapsed - doneAt - LINGER_MS + 300) / 300f);
		UI.alpha = Math.min(in, out);

		int w = 170;
		int h = 36 + total * 11;
		int x = graphics.guiWidth() / 2 - w / 2;
		int y = 22 + Math.round((1f - in) * -8);
		UI.round(x, y, w, h, 3, UI.theme.panel() & 0xF0FFFFFF);
		boolean failed = results.stream().anyMatch(java.util.Objects::nonNull);
		UI.outline(x, y, w, h, 1, failed ? UI.theme.danger() : UI.theme.accent());
		float progress = Anim.clamp01(elapsed / (float) doneAt);
		UI.fill(x + 1, y + 1, x + 1 + Math.round((w - 2) * progress), y + 2, failed ? UI.theme.danger() : UI.theme.accent());

		UI.logo(x + 5, y + 4, 10);
		String dots = results.size() < total ? ".".repeat((int) ((elapsed / 300) % 4)) : "";
		UI.text("AutoDonut Booting Up" + dots, x + 19, y + 6, UI.theme.text());
		// Why it's booting, on its own line so it never runs into the title.
		UI.text(UI.trim(reason, w - 24), x + 19, y + 16, UI.theme.textMuted());
		float base = UI.alpha;
		for (int i = 0; i < results.size(); i++) {
			BootSequence.Failure r = results.get(i);
			UI.alpha = base * Anim.clamp01((elapsed - (i + 1) * STEP_MS) / 200f);
			String line = (r == null ? "✔ " : "✘ ") + BootSequence.stepLabel(i);
			UI.text(line, x + 8, y + 29 + i * 11, r == null ? UI.theme.textMuted() : UI.theme.danger());
			if (r != null) UI.textRight("see chat", x + w - 5, y + 29 + i * 11, UI.WARNING);
		}
		UI.alpha = base;
	}

	/** Failures from the last overlay run, reported once in chat (unless streaming). */
	public static void reportFailures(Minecraft mc) {
		if (startedAt >= 0 || results.isEmpty() || mc.player == null) return;
		for (BootSequence.Failure f : results) {
			if (f != null && !Compat.streamerMode()) {
				mc.player.sendSystemMessage(Component.literal("[AutoDonut] " + f.step() + " failed: " + f.reason()
						+ " Likely cause: " + f.cause()));
			}
		}
		results.clear();
	}
}
