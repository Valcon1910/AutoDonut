package com.autodonut.client.ui;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import com.autodonut.client.Compat;
import com.autodonut.client.Lockdown;
import com.autodonut.client.ServerProbe;
import com.autodonut.client.auction.AutoAuctionController;
import com.autodonut.client.auction.AutoBuyController;
import com.autodonut.client.config.AutoDonutConfig;

/**
 * Top-centre card showing what the crosshair is on (Jade-style, without the mod name), fused with
 * the AutoDonut status line. Replaces {@link StatusHud} and Jade's tooltip while it's on.
 */
public final class LookHud {
	/** Drawing context; swapped for the screen's while drawing a preview. */
	private static Ui UI = new Ui();
	private static final Anim VISIBLE = new Anim(0, 8);
	private static final Anim WIDTH = new Anim(0, 14);
	private static final Anim HEIGHT = new Anim(0, 14);
	private static final Anim LOOK = new Anim(0, 10);
	private static final Anim FADE = new Anim(0, 16);
	private static final Anim STATUS = new Anim(0, 10);
	private static final Anim EXPANDED = new Anim(0, 10);
	private static final Anim HEALTH = new Anim(0, 10);
	private static final Anim PROGRESS = new Anim(0, 18);
	private static final long BADGE_IDLE_MS = 10_000;
	private static final int STATUS_H = 16;

	/** Friendly labels for block-state properties worth showing. */
	private static final Map<String, String> LABELS = Map.ofEntries(
			Map.entry("age", "Growth"), Map.entry("level", "Level"), Map.entry("honey_level", "Honey"),
			Map.entry("bites", "Bites"), Map.entry("charges", "Charge"), Map.entry("power", "Power"),
			Map.entry("note", "Note"), Map.entry("lit", "Lit"), Map.entry("open", "Open"),
			Map.entry("powered", "Powered"), Map.entry("candles", "Candles"), Map.entry("pickles", "Pickles"),
			Map.entry("eggs", "Eggs"), Map.entry("delay", "Delay"), Map.entry("moisture", "Moisture"));

	private static long lastFrame = System.nanoTime();
	private static long lastActivity = System.currentTimeMillis();

	/** What the card shows for one target; rebuilt only when the target changes. */
	private record Info(Object key, ItemStack icon, String name, List<String> details, int[] colors,
			LivingEntity living, BlockPos pos, int width, List<ItemStack> tools, boolean canHarvest) { }

	// Cache keys for the current target, compared without allocating.
	private static BlockState cachedState;
	private static BlockPos cachedPos;
	private static Entity cachedEntity;
	private static ItemStack cachedHeld;
	private static long cachedAt;
	/** HUD part toggles the cached info was built with. */
	private static int cachedFlags;
	private static Info current;
	/** What's drawn (lags behind {@link #current} while cross-fading). */
	private static Info shown;
	private static int hpShown = -1, hpMaxShown = -1;
	private static String hpText = "";

	// MultiPlayerGameMode's mining progress is private: read by reflection, skipped if it ever moves.
	private static Field destroyProgress, isDestroying, destroyPos;
	private static boolean reflectFailed;

	private LookHud() {
	}

	/** The fused HUD is drawing, so the old status label and Jade's tooltip stay hidden. */
	public static boolean active() {
		return com.autodonut.client.config.AutoDonutConfig.get().showHud && Compat.lookHudEnabled() && !Compat.streamerMode();
	}

	public static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		AutoDonutConfig cfg = AutoDonutConfig.get();
		AutoAuctionController auction = AutoAuctionController.get();
		AutoBuyController buy = AutoBuyController.get();

		long now = System.nanoTime();
		UI.dt = Math.min(0.1f, (now - lastFrame) / 1_000_000_000f);
		lastFrame = now;

		boolean on = active() && cfg.showHud && mc.player != null && mc.level != null;

		// Status part: same rules as StatusHud.
		boolean noResponse = !ServerProbe.responding();
		boolean lagWarning = auction.lagging() || noResponse;
		// The look-at part works in any world; the AutoDonut status row only on Donut SMP.
		boolean allowed = on && cfg.showHud && cfg.hudStatusRow && !Lockdown.active()
				&& com.autodonut.client.ServerContext.isOnDonut();
		boolean wanted = allowed && (auction.isActive() || buy.isActive() || auction.quickSelling() || lagWarning);
		net.minecraft.client.gui.screens.Screen open = mc.gui.screen();
		boolean expanded = wanted && (open == null || auction.isHidden(open) || buy.isHidden(open));
		long ms = System.currentTimeMillis();
		if (expanded || auction.busy() || buy.busy() || auction.quickSelling() || lagWarning) lastActivity = ms;
		boolean statusOn = allowed && (expanded || ms - lastActivity <= BADGE_IDLE_MS);
		if (auction.inCombat() && !lagWarning) statusOn = false;
		expanded = expanded && statusOn;

		// Look part: only with no screen open, like Jade.
		Info target = on && open == null ? target(mc) : null;
		// Switching between targets is instant (no cross-fade or resize) so sweeping across blocks
		// never flickers; only appearing and disappearing are animated.
		boolean switched = false;
		if (target != null && (shown == null || !shown.key().equals(target.key()))) {
			switched = shown != null && LOOK.get() > 0.05f;
			shown = target;
			FADE.snap(1);
			HEALTH.snap(healthFraction(target));
			PROGRESS.snap(0);
			hpShown = -1;
		} else if (target != null) {
			shown = target;
		}
		boolean hasLook = target != null;
		LOOK.set(hasLook ? 1 : 0);
		STATUS.set(statusOn ? 1 : 0);
		EXPANDED.set(expanded ? 1 : 0);
		VISIBLE.set(hasLook || statusOn ? 1 : 0);
		float v = VISIBLE.update(UI.dt);
		float look = Anim.easeInOut(LOOK.update(UI.dt));
		float fade = FADE.update(UI.dt);
		float st = Anim.easeInOut(STATUS.update(UI.dt));
		float e = Anim.easeInOut(EXPANDED.update(UI.dt));
		if (look <= 0.01f && !hasLook) shown = null;
		if (v <= 0.01f) {
			WIDTH.snap(0);
			HEIGHT.snap(0);
			return;
		}

		UI.g = graphics;
		UI.font = mc.font;
		UI.theme = Theme.current(cfg);

		boolean showBuy = !auction.quickSelling() && !auction.busy()
				&& (buy.busy() || (buy.isActive() && !auction.isActive()));
		boolean system = (noResponse || auction.lagging()) && !auction.busy() && !auction.quickSelling() && !buy.busy();
		String label = system ? "System" : showBuy ? "Auto Buy" : "Auto Auction";
		String status = system ? (noResponse ? "Server not responding (Lag)" : auction.lagReason())
				: noResponse ? "Server not responding (Lag)" : showBuy ? buy.status() : auction.status();

		// Target size; the card eases toward it.
		Style sty = Style.of(cfg.hudCardStyle);
		int statusH = statusHeight(sty);
		String inline = inline(sty, shown, cfg);
		int lookH = lookHeight(sty, shown);
		int lookW = lookWidth(sty, shown, inline);
		int statusW = statusWidth(sty, label, status, e);
		int tw = Math.max(statusOn ? statusW : 0, hasLook ? lookW : 0);
		int th = (hasLook ? lookH : 0) + (statusOn ? statusH : 0) + (hasLook && statusOn ? 1 : 0);
		if (tw > 0) WIDTH.set(tw);
		if (th > 0) HEIGHT.set(th);
		if (switched) {
			if (tw > 0) WIDTH.snap(tw);
			if (th > 0) HEIGHT.snap(th);
		}
		if (WIDTH.get() <= 0) WIDTH.snap(tw);
		if (HEIGHT.get() <= 0) HEIGHT.snap(th);
		int w = Math.max(18, Math.round(WIDTH.update(UI.dt)));
		int h = Math.max(statusH, Math.round(HEIGHT.update(UI.dt)));

		int x = graphics.guiWidth() / 2 - Math.round(w * sty.scale / 2f);
		int y = 4 - Math.round((1f - Anim.easeOutCubic(v)) * 10);
		float base = v * 0.92f;
		float hf = 0;
		if (shown != null && shown.living() != null) {
			HEALTH.set(healthFraction(shown));
			hf = HEALTH.update(UI.dt);
		}
		float pf = shown != null && shown.living() == null && shown.pos() != null && cfg.hudMining
				? smoothProgress(destroyProgress(mc, shown.pos())) : 0;
		UI.g.pose().pushMatrix();
		// Scale around the card's top-left corner so everything below draws in card units.
		UI.g.pose().translate(x, y);
		UI.g.pose().scale(sty.scale);
		paint(sty, shown, inline, lookH, w, h, base, base * (1f - cfg.hudTransparency / 100f), look, fade, st, e, v,
				hasLook, label, status, lagWarning, hf, pf, cfg);
		UI.g.pose().popMatrix();
	}

	/**
	 * Draws a card at (0, 0) in the current pose with {@link #UI}: background, look section and
	 * status row. Shared by the live HUD and the HUD page's style previews.
	 */
	private static void paint(Style sty, Info shown, String inline, int lookH, int w, int h, float base, float bgAlpha,
			float look, float fade, float st, float e, float v, boolean hasLook, String label, String status,
			boolean lagWarning, float hf, float pf, AutoDonutConfig cfg) {
		int x = 0, y = 0;
		int statusH = statusHeight(sty);
		int cx = sty.pad + 20;

		if (sty.background) {
			float bg = bgAlpha;
			UI.alpha = bg * 0.6f;
			UI.round(x + 1, y + 2, w, h, sty.radius, UI.theme.shadow());
			UI.alpha = bg;
			int border = sty == Style.JADE ? Anim.lerpColor(UI.theme.border(), UI.theme.accent(), 0.45f) : UI.theme.border();
			UI.card(x, y, w, h, sty.radius, UI.theme.panel(), border);
		}

		UI.scissor(x, y, x + w, y + h);
		// Look section
		if (shown != null && look > 0.01f) {
			float a = base * look * fade;
			UI.alpha = a;
			if (sty.stripe) UI.fill(x + 1, y + 6, x + 3, y + Math.min(h - 6, lookH - 4), UI.theme.accent());
			UI.item(shown.icon(), x + sty.pad, y + sty.iconY);
			int nameRoom = w - cx - 8 - (inline == null ? 0 : 8 + UI.width(inline));
			name(sty, UI.trim(shown.name(), nameRoom), x + cx, y + sty.nameY);
			if (inline != null) {
				int ic = inline.equals("\u2714") ? UI.theme.success() : inline.equals("\u2718") ? UI.theme.danger() : UI.theme.textMuted();
				txt(sty, inline, x + w - 8 - UI.width(inline), y + sty.nameY, ic);
			}
			int ly = y + sty.bodyY;
			if (!sty.compact && !shown.tools().isEmpty()) {
				// Tool icons at the tier needed, then whether the held item can harvest it.
				int tx = x + cx;
				for (ItemStack tool : shown.tools()) {
					UI.g.pose().pushMatrix();
					UI.g.pose().translate(tx, ly - 2);
					UI.g.pose().scale(0.875f);
					UI.item(tool, 0, 0);
					UI.g.pose().popMatrix();
					tx += 16;
				}
				txt(sty, shown.canHarvest() ? "\u2714" : "\u2718", tx + 2, ly + 2, shown.canHarvest() ? UI.theme.success() : UI.theme.danger());
				ly += 16;
			}
			if (!sty.compact) {
				for (int i = 0; i < shown.details().size(); i++) {
					txt(sty, shown.details().get(i), x + cx, ly, shown.colors()[i]);
					ly += 10;
				}
			}
			if (shown.living() != null) {
				LivingEntity le = shown.living();
				if (!sty.compact && cfg.hudHealth) {
					String hpt = healthText(le);
					int bw = Math.max(40, w - cx - 12 - UI.width(hpt));
					bar(sty, x + cx, ly + 3, bw, hf, hf > 0.5f ? UI.theme.success() : hf > 0.25f ? Ui.WARNING : UI.theme.danger());
					txt(sty, hpt, x + cx + bw + 5, ly, UI.theme.textMuted());
					ly += 10;
				}
				int armor = le.getArmorValue();
				if (!sty.compact && cfg.hudArmor && armor > 0) txt(sty, "Armor: " + armor, x + cx, ly, UI.theme.textMuted());
			} else if (shown.pos() != null && cfg.hudMining) {
				// Compact has no row for it, so the bar runs along the card's bottom edge.
				if (pf > 0.01f) {
					if (sty.compact) bar(sty, x + 4, y + lookH - 3, w - 8, pf, UI.theme.accent());
					else bar(sty, x + cx, ly + 3, w - cx - 12, pf, UI.theme.accent());
				}
			}
		}
		// Divider + status row
		if (st > 0.01f) {
			int sy = y + h - statusH;
			if ((hasLook || look > 0.01f) && sty.background) {
				UI.alpha = base * st * Math.max(look, 0.0f);
				UI.fill(x + 6, sy - 1, x + w - 6, sy, UI.theme.border());
			}
			int tx = x + 6;
			if (sty.logo) {
				UI.alpha = 1f;
				int full = sty.compact ? 9 : 12;
				int size = Math.round(full * Anim.easeInOut(v * st));
				if (size >= 2) UI.logo(x + 9 - size / 2, sy + statusH / 2 - size / 2, size);
				tx = x + 17;
			}
			if (e > 0.05f) {
				UI.alpha = base * st * e;
				int ty = sy + (statusH - 8) / 2;
				txt(sty, label, tx, ty, UI.theme.text());
				txt(sty, status, tx + UI.width(label) + 8, ty, lagWarning ? Ui.WARNING : UI.theme.textMuted());
			}
		}
		UI.endScissor();
	}

	private static int statusHeight(Style sty) {
		return sty.compact ? 12 : STATUS_H;
	}

	/** Compact style's one inline detail: harvest check, health, or the first detail line. */
	private static String inline(Style sty, Info shown, AutoDonutConfig cfg) {
		if (shown == null || !sty.compact) return null;
		if (!shown.tools().isEmpty()) return shown.canHarvest() ? "\u2714" : "\u2718";
		if (shown.living() != null && cfg.hudHealth) return healthText(shown.living());
		return shown.details().isEmpty() ? null : shown.details().get(0);
	}

	private static int lookHeight(Style sty, Info shown) {
		if (shown == null) return 0;
		if (sty.compact) return 20;
		return Math.max(sty.bodyY + shown.details().size() * 10 + extraRows(shown) * 10
				+ (shown.tools().isEmpty() ? 0 : 16) + 2, sty.iconY + 18);
	}

	private static int lookWidth(Style sty, Info shown, String inline) {
		if (shown == null) return 0;
		int cx = sty.pad + 20;
		return sty.compact ? cx + nameWidth(sty, shown.name()) + (inline == null ? 0 : 8 + UI.width(inline)) + 8
				: shown.width() - 28 + cx;
	}

	private static int statusWidth(Style sty, String label, String status, float e) {
		return (sty.logo ? 18 : 6) + Math.round((UI.width(label) + 8 + UI.width(status) + 8) * e);
	}

	private static Info previewInfo;

	/**
	 * Draws a sample card ("Diamond Ore" with a pickaxe, a detail line, a half-done mining bar and
	 * a status line) in the given style, scaled down to fit and centred in the rect.
	 */
	public static void drawPreview(Ui ui, Style sty, int rx, int ry, int rw, int rh) {
		Ui saved = UI;
		UI = ui;
		try {
			if (previewInfo == null) {
				previewInfo = new Info("preview", new ItemStack(Items.DIAMOND_ORE), "Diamond Ore", List.of("Growth: 75%"),
						new int[]{ui.theme.textMuted()}, null, BlockPos.ZERO, 110,
						List.of(new ItemStack(Items.IRON_PICKAXE)), true);
			}
			previewInfo.colors()[0] = ui.theme.textMuted();
			AutoDonutConfig cfg = AutoDonutConfig.get();
			String label = "Auto Auction", status = "Next listing in 4s";
			String inline = inline(sty, previewInfo, cfg);
			int lookH = lookHeight(sty, previewInfo);
			int statusH = statusHeight(sty);
			int w = Math.max(lookWidth(sty, previewInfo, inline), statusWidth(sty, label, status, 1f));
			int h = lookH + statusH + 1;
			float scale = Math.min(1f, Math.min(rw / (w * sty.scale), rh / (h * sty.scale))) * sty.scale;
			int px = rx + Math.round((rw - w * scale) / 2f);
			int py = ry + Math.round((rh - h * scale) / 2f);
			float a = ui.alpha;
			ui.g.pose().pushMatrix();
			ui.g.pose().translate(px, py);
			ui.g.pose().scale(scale);
			paint(sty, previewInfo, inline, lookH, w, h, a, a * (1f - cfg.hudTransparency / 100f), 1f, 1f, 1f, 1f, 1f,
					true, label, status, false, 0f, 0.55f, cfg);
			ui.g.pose().popMatrix();
			ui.alpha = a;
		} finally {
			UI = saved;
		}
	}

	/** Look-at card styles; one draw path reads these layout parameters. */
	public enum Style {
		JADE(2, 4, 3, 5, 16, 1f, false, true, false, false, false, false),
		AUTODONUT(6, 8, 5, 9, 24, 1f, true, true, true, false, true, false),
		COMPACT(8, 5, 2, 6, 20, 1f, false, true, true, true, true, false),
		LARGE(7, 10, 6, 10, 26, 1.25f, true, true, true, false, true, false),
		MINIMAL(0, 2, 2, 6, 20, 1f, false, false, false, false, true, true);

		public static final String[] LABELS = {"Jade", "Donut", "Compact", "Large", "Minimal"};
		public static final String[] TOOLTIPS = {
				"Like Jade's own tooltip: a compact dark box with a thin border",
				"AutoDonut's card with the accent stripe and logo (default)",
				"A single small row: icon, name and the key detail",
				"Everything 25% bigger with roomier spacing",
				"No background, just icons and shadowed text"};

		final int radius, pad, iconY, nameY, bodyY;
		final float scale;
		final boolean stripe, background, boldName, compact, logo, shadow;

		Style(int radius, int pad, int iconY, int nameY, int bodyY, float scale, boolean stripe, boolean background,
				boolean boldName, boolean compact, boolean logo, boolean shadow) {
			this.radius = radius;
			this.pad = pad;
			this.iconY = iconY;
			this.nameY = nameY;
			this.bodyY = bodyY;
			this.scale = scale;
			this.stripe = stripe;
			this.background = background;
			this.boldName = boldName;
			this.compact = compact;
			this.logo = logo;
			this.shadow = shadow;
		}

		/** The style with that name, or the AutoDonut card. */
		public static Style of(String name) {
			for (Style s : values()) if (s.name().equals(name)) return s;
			return AUTODONUT;
		}
	}

	private static void txt(Style sty, String s, int x, int y, int color) {
		if (sty.shadow) UI.shadowText(s, x, y, color);
		else UI.text(s, x, y, color);
	}

	private static void name(Style sty, String s, int x, int y) {
		if (sty.boldName) UI.bold(s, x, y, UI.theme.text());
		else txt(sty, s, x, y, UI.theme.text());
	}

	private static int nameWidth(Style sty, String s) {
		return sty.boldName ? UI.boldWidth(s) : UI.width(s);
	}

	/** Progress bar; thinner without a background. */
	private static void bar(Style sty, int x, int y, int w, float fraction, int color) {
		int bh = sty.background ? 3 : 2;
		UI.fill(x, y, x + w, y + bh, UI.theme.track());
		int fw = Math.round(Anim.clamp01(fraction) * w);
		if (fw > 0) UI.fill(x, y, x + fw, y + bh, color);
	}

	/** "x / y ❤", rebuilt only when the numbers change. */
	private static String healthText(LivingEntity le) {
		int hp = Math.round(le.getHealth()), max = Math.round(le.getMaxHealth());
		if (hp != hpShown || max != hpMaxShown) {
			hpShown = hp;
			hpMaxShown = max;
			hpText = hp + " / " + max + " \u2764";
		}
		return hpText;
	}

	/** Extra rows below the details: health bar (+ armor) for mobs, the mining bar for blocks. */
	private static int extraRows(Info info) {
		AutoDonutConfig cfg = AutoDonutConfig.get();
		if (info.living() != null) return (cfg.hudHealth ? 1 : 0) + (cfg.hudArmor && info.living().getArmorValue() > 0 ? 1 : 0);
		return info.pos() != null && cfg.hudMining ? 1 : 0;
	}

	private static float healthFraction(Info info) {
		LivingEntity le = info.living();
		if (le == null || le.getMaxHealth() <= 0) return 0;
		return Anim.clamp01(le.getHealth() / le.getMaxHealth());
	}

	/** The cached info for the crosshair target, rebuilt when the target, its state or the held item changes. */
	private static Info target(Minecraft mc) {
		HitResult hit = mc.hitResult;
		ItemStack held = mc.player.getMainHandItem();
		AutoDonutConfig cfg = AutoDonutConfig.get();
		int flags = (cfg.hudHarvest ? 1 : 0) | (cfg.hudBlockDetails ? 2 : 0) | (cfg.hudItemCount ? 4 : 0) | (cfg.hudHealth ? 8 : 0);
		if (flags != cachedFlags) {
			cachedFlags = flags;
			current = null;
		}
		if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) {
			BlockPos pos = bh.getBlockPos();
			BlockState state = mc.level.getBlockState(pos);
			if (state.isAir()) return null;
			if (current != null && state == cachedState && pos.equals(cachedPos) && held == cachedHeld) return current;
			cachedState = state;
			cachedPos = pos.immutable();
			cachedEntity = null;
			cachedHeld = held;
			current = blockInfo(mc, cachedPos, state);
			return current;
		}
		if (hit instanceof EntityHitResult eh && hit.getType() == HitResult.Type.ENTITY) {
			Entity entity = eh.getEntity();
			long ms = System.currentTimeMillis();
			// Item stacks on the ground can merge, so refresh entities every half second.
			if (current != null && entity == cachedEntity && ms - cachedAt < 500) return current;
			cachedEntity = entity;
			cachedState = null;
			cachedPos = null;
			cachedAt = ms;
			current = entityInfo(mc.font, entity);
			return current;
		}
		cachedState = null;
		cachedPos = null;
		cachedEntity = null;
		current = null;
		return null;
	}

	private static Info blockInfo(Minecraft mc, BlockPos pos, BlockState state) {
		Theme t = Theme.current(AutoDonutConfig.get());
		List<String> lines = new ArrayList<>();
		List<Integer> colors = new ArrayList<>();
		// Harvestability
		boolean canHarvest = !state.requiresCorrectToolForDrops() || mc.player.hasCorrectToolForDrops(state);
		AutoDonutConfig cfg = AutoDonutConfig.get();
		List<ItemStack> tools = cfg.hudHarvest ? toolsFor(state) : List.of();
		// Readable block-state properties
		if (cfg.hudBlockDetails) for (Property<?> p : state.getProperties()) {
			String name = p.getName();
			String label = name.startsWith("age") ? "Growth" : LABELS.get(name);
			if (label == null) continue;
			Object value = state.getValue(p);
			String text;
			if (value instanceof Boolean b) {
				text = switch (name) {
					case "open" -> b ? "Open" : "Closed";
					case "lit" -> b ? "Lit" : "Not lit";
					case "powered" -> b ? "Powered" : "Not powered";
					default -> label + ": " + (b ? "Yes" : "No");
				};
			} else if (value instanceof Integer i) {
				int max = 0;
				for (Object o : p.getPossibleValues()) if (o instanceof Integer n) max = Math.max(max, n);
				if (label.equals("Growth")) {
					text = max <= 0 ? "Growth: " + i : i >= max ? "Growth: Mature" : "Growth: " + Math.round(i * 100f / max) + "%";
				} else if (label.equals("Power") || label.equals("Note") || label.equals("Delay")) {
					text = label + ": " + i;
				} else {
					text = label + ": " + i + " / " + max;
				}
			} else {
				continue;
			}
			lines.add(text);
			colors.add(t.textMuted());
		}
		ItemStack icon = new ItemStack(state.getBlock().asItem());
		String name = state.getBlock().getName().getString();
		return build(mc.font, state, icon, name, lines, colors, null, pos, true, tools, canHarvest);
	}

	private static Info entityInfo(Font font, Entity entity) {
		Theme t = Theme.current(AutoDonutConfig.get());
		List<String> lines = new ArrayList<>();
		List<Integer> colors = new ArrayList<>();
		ItemStack icon = ItemStack.EMPTY;
		String name = entity.getDisplayName().getString();
		LivingEntity living = null;
		if (entity instanceof ItemEntity ie) {
			ItemStack stack = ie.getItem();
			icon = stack.copy();
			name = stack.getHoverName().getString();
			if (AutoDonutConfig.get().hudItemCount) {
				lines.add("Count: " + stack.getCount());
				colors.add(t.textMuted());
			}
		} else {
			ItemStack pick = entity.getPickResult();
			if (pick != null) icon = pick;
			if (entity instanceof LivingEntity le) living = le;
		}
		return build(font, Integer.valueOf(entity.getId()), icon, name, lines, colors, living, null, false);
	}

	private static Info build(Font font, Object key, ItemStack icon, String name, List<String> lines, List<Integer> colors,
			LivingEntity living, BlockPos pos, boolean block) {
		return build(font, key, icon, name, lines, colors, living, pos, block, List.of(), true);
	}

	private static Info build(Font font, Object key, ItemStack icon, String name, List<String> lines, List<Integer> colors,
			LivingEntity living, BlockPos pos, boolean block, List<ItemStack> tools, boolean canHarvest) {
		int[] cols = new int[colors.size()];
		for (int i = 0; i < cols.length; i++) cols[i] = colors.get(i);
		UI.font = font;
		int w = UI.boldWidth(name);
		for (String l : lines) w = Math.max(w, UI.width(l));
		if (living != null && AutoDonutConfig.get().hudHealth) w = Math.max(w, 110);
		if (block) w = Math.max(w, 70);
		// Key also covers the block at that spot, so a different block in the same place cross-fades.
		Object k = block ? List.of(pos, ((BlockState) key).getBlock()) : key;
		w = Math.max(w, tools.size() * 16 + 14);
		return new Info(k, icon, name, List.copyOf(lines), cols, living, pos, Math.min(260, w + 40), tools, canHarvest);
	}

	private static float lastSample, rate;
	private static long sampleAt;

	/**
	 * The game only updates mining progress once per tick (20 steps a second), so between
	 * ticks the bar keeps moving at the measured rate instead of jumping step by step.
	 */
	private static float smoothProgress(float sample) {
		long now = System.nanoTime();
		if (sample <= 0f) {
			lastSample = 0;
			rate = 0;
			sampleAt = now;
			return 0;
		}
		if (sample != lastSample) {
			float dt = (now - sampleAt) / 1_000_000_000f;
			if (sample > lastSample && dt > 0.01f && dt < 0.5f) rate = (sample - lastSample) / dt;
			else if (sample < lastSample) rate = 0;
			lastSample = sample;
			sampleAt = now;
		}
		float ahead = rate * Math.min(0.06f, (now - sampleAt) / 1_000_000_000f);
		return Math.min(1f, lastSample + ahead);
	}

	/**
	 * The tools that mine a block, each at the lowest tier that can harvest it (stone pickaxe for
	 * iron ore, wooden for stone...). Several when more than one tool type works.
	 */
	private static List<ItemStack> toolsFor(BlockState state) {
		String tier = state.is(BlockTags.NEEDS_DIAMOND_TOOL) ? "diamond"
				: state.is(BlockTags.NEEDS_IRON_TOOL) ? "iron"
				: state.is(BlockTags.NEEDS_STONE_TOOL) ? "stone" : "wooden";
		List<ItemStack> out = new ArrayList<>();
		if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) out.add(tool(tier, "pickaxe"));
		if (state.is(BlockTags.MINEABLE_WITH_AXE)) out.add(tool(tier, "axe"));
		if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) out.add(tool(tier, "shovel"));
		if (state.is(BlockTags.MINEABLE_WITH_HOE)) out.add(tool(tier, "hoe"));
		out.removeIf(ItemStack::isEmpty);
		return out;
	}

	private static ItemStack tool(String tier, String kind) {
		var item = net.minecraft.core.registries.BuiltInRegistries.ITEM
				.getValue(net.minecraft.resources.Identifier.withDefaultNamespace(tier + "_" + kind));
		return item == null ? ItemStack.EMPTY : new ItemStack(item);
	}

	/** Mining progress (0..1) on the given block, or 0 when not mining it / not readable. */
	private static float destroyProgress(Minecraft mc, BlockPos pos) {
		if (reflectFailed || mc.gameMode == null) return 0;
		try {
			if (destroyProgress == null) {
				Class<?> c = mc.gameMode.getClass();
				destroyProgress = c.getDeclaredField("destroyProgress");
				isDestroying = c.getDeclaredField("isDestroying");
				destroyPos = c.getDeclaredField("destroyBlockPos");
				destroyProgress.setAccessible(true);
				isDestroying.setAccessible(true);
				destroyPos.setAccessible(true);
			}
			if (!isDestroying.getBoolean(mc.gameMode)) return 0;
			if (!pos.equals(destroyPos.get(mc.gameMode))) return 0;
			return Anim.clamp01(destroyProgress.getFloat(mc.gameMode));
		} catch (ReflectiveOperationException | RuntimeException ex) {
			reflectFailed = true;
			return 0;
		}
	}
}
