package com.autodonut.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import com.autodonut.client.auction.AutoAuctionController;
import com.autodonut.client.auction.InventoryActions;
import com.autodonut.client.config.AutoDonutConfig;
import com.autodonut.client.ui.BootErrorScreen;

/**
 * The "AutoDonut Booting Up" checks on the connect screen. Each step runs a real check when
 * its turn comes; its line fades in with a tick, or a cross if it failed. Failures are
 * explained in a window once the player is in the world.
 */
public final class BootSequence {
	/** A check returns null when fine, or a human explanation of what went wrong. */
	private record Step(String label, Supplier<String> check) { }

	/** A failed step, with the explanation and a guess at what caused it. */
	public record Failure(String step, String reason, String cause) { }

	private static final long STEP_MS = 170;
	private static final List<Step> STEPS = List.of(
			new Step("Loading settings", AutoDonutConfig::check),
			new Step("Connecting features", () -> {
				Minecraft mc = Minecraft.getInstance();
				return mc.gameMode != null && !InventoryActions.available(mc)
						? "Inventory actions aren't available, so items can't be prepared for listing." : null;
			}),
			new Step("Preparing Auto Auction", () -> {
				AutoAuctionController.get().onDisconnect();
				return null;
			}),
			new Step("Preparing Auto Buy", () -> {
				com.autodonut.client.auction.AutoBuyController.get().onDisconnect();
				return null;
			}),
			new Step("Safety checks", () -> {
				AutoDonutConfig cfg = AutoDonutConfig.get();
				return cfg.maxDelaySeconds < cfg.minDelaySeconds ? "The maximum delay is lower than the minimum delay." : null;
			})
	);

	private static long startedAt = -1;
	private static int done;
	private static final List<Boolean> results = new ArrayList<>();
	private static final List<Failure> failures = new ArrayList<>();

	private BootSequence() {
	}

	public static void start() {
		if (startedAt < 0) {
			startedAt = System.currentTimeMillis();
			done = 0;
			results.clear();
			failures.clear();
		}
	}

	public static void reset() {
		startedAt = -1;
		done = 0;
		results.clear();
	}

	public static int stepCount() {
		return STEPS.size();
	}

	public static String stepLabel(int i) {
		return STEPS.get(i).label();
	}

	/** The step as a finished action ("Loaded settings"), for the in-game check overlay. */
	public static String stepDoneLabel(int i) {
		return switch (STEPS.get(i).label()) {
			case "Loading settings" -> "Loaded settings";
			case "Connecting features" -> "Connected features";
			case "Preparing Auto Auction" -> "Prepared Auto Auction";
			case "Preparing Auto Buy" -> "Prepared Auto Buy";
			case "Safety checks" -> "Checked safety";
			default -> STEPS.get(i).label();
		};
	}

	/** Runs one step; returns null when it passed, otherwise what failed and why. */
	public static Failure runStep(int i) {
		Step step = STEPS.get(i);
		try {
			String problem = step.check().get();
			return problem == null ? null : new Failure(step.label(), problem, "A setting or file used by AutoDonut.");
		} catch (Throwable t) {
			return new Failure(step.label(), t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage()), blame(t));
		}
	}

	private static void runNext() {
		Step step = STEPS.get(done);
		String problem;
		String cause;
		try {
			problem = step.check().get();
			cause = problem == null ? null : "A setting or file used by AutoDonut.";
		} catch (Throwable t) {
			problem = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
			cause = blame(t);
		}
		results.add(problem == null);
		if (problem != null) failures.add(new Failure(step.label(), problem, cause));
		done++;
	}

	/**
	 * Names the most likely culprit of an exception: the first stack frame that belongs to a
	 * mod other than AutoDonut, Minecraft, Fabric or Java.
	 */
	static String blame(Throwable t) {
		for (Throwable cur = t; cur != null; cur = cur.getCause()) {
			for (StackTraceElement frame : cur.getStackTrace()) {
				String cls = frame.getClassName();
				if (cls.startsWith("java.") || cls.startsWith("jdk.") || cls.startsWith("sun.") || cls.startsWith("net.minecraft.")
						|| cls.startsWith("com.mojang.") || cls.startsWith("com.autodonut.") || cls.startsWith("net.fabricmc.")
						|| cls.startsWith("org.spongepowered.") || cls.startsWith("com.llamalad7.") || cls.startsWith("knot")) continue;
				String lower = cls.toLowerCase(Locale.ROOT);
				for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
					String id = mod.getMetadata().getId().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
					if (id.length() > 3 && lower.replace("_", "").contains(id)) {
						return "The mod \"" + mod.getMetadata().getName() + "\" (" + cls + ").";
					}
				}
				return "Another mod (code in " + cls.substring(0, Math.max(0, cls.lastIndexOf('.'))) + ").";
			}
		}
		return "AutoDonut itself, or a Minecraft / Fabric API update it doesn't support yet.";
	}

	/** Draws the step lines below the connect screen's status. */
	public static void render(GuiGraphicsExtractor graphics) {
		if (startedAt < 0 || Compat.streamerMode()) return;
		long elapsed = System.currentTimeMillis() - startedAt;
		while (done < STEPS.size() && elapsed >= (done + 1) * STEP_MS) runNext();

		Minecraft mc = Minecraft.getInstance();
		int cx = graphics.guiWidth() / 2;
		int y = graphics.guiHeight() / 2 - 30;
		// Finished steps in past tense; the step running now right below them, in present tense.
		int shown = Math.min(STEPS.size(), done + 1);
		for (int i = 0; i < shown; i++) {
			boolean running = i == done;
			float fade = Math.min(1f, (elapsed - i * STEP_MS) / 120f);
			int alpha = Math.max(8, Math.round(fade * 255));
			String text;
			int color;
			if (running) {
				text = STEPS.get(i).label() + ".".repeat((int) ((elapsed / 250) % 4));
				color = 0xFFFFFF;
			} else {
				boolean ok = results.get(i);
				text = (ok ? "\u2714 " : "\u2718 ") + stepDoneLabel(i);
				color = ok ? 0x9AA0AA : 0xE5484D;
			}
			int lineY = y + i * 11 + Math.round((1f - fade) * 4);
			graphics.text(mc.font, text, cx - mc.font.width(running ? STEPS.get(i).label() : text) / 2, lineY, (alpha << 24) | color, false);
		}
	}

	/** Called every client tick: once in the world, explain any failed step. */
	public static void tick(Minecraft mc) {
		if (failures.isEmpty() || mc.player == null || mc.gui.screen() != null) return;
		if (!Compat.streamerMode()) mc.gui.setScreen(new BootErrorScreen(List.copyOf(failures)));
		failures.clear();
	}
}
