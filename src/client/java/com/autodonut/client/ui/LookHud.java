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
	private static final Ui UI = new Ui();
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
			LivingEntity living, BlockPos pos, int width) { }

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
		if (target != null && (shown == null || !shown.key().equals(target.key()))) {
			if (shown == null || LOOK.get() < 0.05f || FADE.get() < 0.05f) {
				boolean fresh = shown == null || LOOK.get() < 0.05f;
				shown = target;
				FADE.set(1);
				if (fresh) FADE.snap(1);
				HEALTH.snap(healthFraction(target));
				PROGRESS.snap(0);
				hpShown = -1;
			} else {
				FADE.set(0);
			}
		} else if (target != null) {
			shown = target;
			FADE.set(1);
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
		int statusW = 18 + Math.round((UI.width(label) + 8 + UI.width(status) + 8) * e);
		int lookH = shown == null ? 0 : 26 + shown.details().size() * 10 + extraRows(shown) * 10;
		int tw = Math.max(statusOn ? statusW : 0, hasLook && shown != null ? shown.width() : 0);
		int th = (hasLook ? lookH : 0) + (statusOn ? STATUS_H : 0) + (hasLook && statusOn ? 1 : 0);
		if (tw > 0) WIDTH.set(tw);
		if (th > 0) HEIGHT.set(th);
		if (WIDTH.get() <= 0) WIDTH.snap(tw);
		if (HEIGHT.get() <= 0) HEIGHT.snap(th);
		int w = Math.max(18, Math.round(WIDTH.update(UI.dt)));
		int h = Math.max(STATUS_H, Math.round(HEIGHT.update(UI.dt)));

		int x = graphics.guiWidth() / 2 - w / 2;
		int y = 4 - Math.round((1f - Anim.easeOutCubic(v)) * 10);
		float base = v * 0.92f;

		float bg = base * (1f - cfg.hudTransparency / 100f);
		UI.alpha = bg * 0.6f;
		UI.round(x + 1, y + 2, w, h, 6, UI.theme.shadow());
		UI.alpha = bg;
		UI.card(x, y, w, h, 6, UI.theme.panel(), UI.theme.border());

		UI.scissor(x, y, x + w, y + h);
		// Look section
		if (shown != null && look > 0.01f) {
			float a = base * look * fade;
			UI.alpha = a;
			UI.fill(x + 1, y + 6, x + 3, y + Math.min(h - 6, lookH - 4), UI.theme.accent());
			UI.item(shown.icon(), x + 8, y + 5);
			UI.bold(UI.trim(shown.name(), w - 36), x + 28, y + 9, UI.theme.text());
			int ly = y + 24;
			for (int i = 0; i < shown.details().size(); i++) {
				UI.text(shown.details().get(i), x + 28, ly, shown.colors()[i]);
				ly += 10;
			}
			if (shown.living() != null) {
				LivingEntity le = shown.living();
				if (cfg.hudHealth) {
				HEALTH.set(healthFraction(shown));
				float hf = HEALTH.update(UI.dt);
				int hp = Math.round(le.getHealth()), max = Math.round(le.getMaxHealth());
				if (hp != hpShown || max != hpMaxShown) {
					hpShown = hp;
					hpMaxShown = max;
					hpText = hp + " / " + max + " ❤";
				}
				int bw = Math.max(40, w - 40 - UI.width(hpText));
				UI.meter(x + 28, ly + 3, bw, hf, hf > 0.5f ? UI.theme.success() : hf > 0.25f ? Ui.WARNING : UI.theme.danger());
				UI.text(hpText, x + 28 + bw + 5, ly, UI.theme.textMuted());
				ly += 10;
				}
				int armor = le.getArmorValue();
				if (cfg.hudArmor && armor > 0) UI.text("Armor: " + armor, x + 28, ly, UI.theme.textMuted());
			} else if (shown.pos() != null && cfg.hudMining) {
				float p = destroyProgress(mc, shown.pos());
				PROGRESS.set(p);
				float pf = PROGRESS.update(UI.dt);
				if (pf > 0.01f) UI.meter(x + 28, ly + 3, w - 40, pf, UI.theme.accent());
			}
		}
		// Divider + status row
		if (st > 0.01f) {
			int sy = y + h - STATUS_H;
			if (hasLook || look > 0.01f) {
				UI.alpha = base * st * Math.max(look, 0.0f);
				UI.fill(x + 6, sy - 1, x + w - 6, sy, UI.theme.border());
			}
			UI.alpha = 1f;
			int size = Math.round(12 * Anim.easeInOut(v * st));
			if (size >= 2) UI.logo(x + 9 - size / 2, sy + 8 - size / 2, size);
			if (e > 0.05f) {
				UI.alpha = base * st * e;
				UI.text(label, x + 17, sy + 4, UI.theme.text());
				UI.text(status, x + 17 + UI.width(label) + 8, sy + 4, lagWarning ? Ui.WARNING : UI.theme.textMuted());
			}
		}
		UI.endScissor();
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
		String tool = state.is(BlockTags.MINEABLE_WITH_PICKAXE) ? "Pickaxe"
				: state.is(BlockTags.MINEABLE_WITH_AXE) ? "Axe"
				: state.is(BlockTags.MINEABLE_WITH_SHOVEL) ? "Shovel"
				: state.is(BlockTags.MINEABLE_WITH_HOE) ? "Hoe" : null;
		boolean canHarvest = !state.requiresCorrectToolForDrops() || mc.player.hasCorrectToolForDrops(state);
		AutoDonutConfig cfg = AutoDonutConfig.get();
		if (cfg.hudHarvest && (tool != null || state.requiresCorrectToolForDrops())) {
			lines.add((tool == null ? "Tool" : tool) + (canHarvest ? "  ✔" : "  ✘"));
			colors.add(canHarvest ? t.success() : t.danger());
		}
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
		return build(mc.font, state, icon, name, lines, colors, null, pos, true);
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
		int[] cols = new int[colors.size()];
		for (int i = 0; i < cols.length; i++) cols[i] = colors.get(i);
		UI.font = font;
		int w = UI.boldWidth(name);
		for (String l : lines) w = Math.max(w, UI.width(l));
		if (living != null && AutoDonutConfig.get().hudHealth) w = Math.max(w, 110);
		if (block) w = Math.max(w, 70);
		// Key also covers the block at that spot, so a different block in the same place cross-fades.
		Object k = block ? List.of(pos, ((BlockState) key).getBlock()) : key;
		return new Info(k, icon, name, List.copyOf(lines), cols, living, pos, Math.min(260, w + 40));
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
