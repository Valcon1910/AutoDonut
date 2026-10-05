package com.autodonut.client.ui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import com.autodonut.client.AutoDonutClient;
import com.autodonut.client.Updater;
import com.autodonut.client.config.AutoDonutConfig;

/**
 * Small scrollable changelog window (opened from the title screen's version text): every
 * version as a heading, with its commits listed below it. The screen behind stays visible.
 */
public class ChangelogScreen extends Screen {
	private static final int HEADER = 26;
	private final Screen parent;
	private final Ui ui = new Ui();
	private final Anim open = new Anim(0, 12);
	private final Anim closeHover = new Anim(0, 16);
	private final Anim scroll = new Anim(0, 18);
	private float scrollTarget;
	private long lastFrame = System.nanoTime();
	private int px, py, pw, ph;
	private List<Line> lines = List.of();
	private int builtFor = -1;

	/** kind: 0 = text, 1 = version heading, 2 = muted note, 3 = gap. */
	private record Line(String text, int kind) { }

	public ChangelogScreen(Screen parent) {
		super(Component.literal("Changelog"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		ui.font = font;
		pw = Math.min(300, width - 32);
		ph = Math.min(220, height - 32);
		px = (width - pw) / 2;
		py = (height - ph) / 2;
		builtFor = -1;
		open.set(1);
		UiSounds.open();
		if (Updater.releases().isEmpty() && Updater.state() != Updater.State.CHECKING) Updater.check();
	}

	private void buildLines() {
		List<Updater.Release> releases = Updater.releases();
		if (builtFor == releases.size()) return;
		builtFor = releases.size();
		List<Line> out = new ArrayList<>();
		int w = pw - 28;
		for (Updater.Release r : releases) {
			if (!out.isEmpty()) out.add(new Line("", 3));
			boolean current = r.version().equals(AutoDonutClient.version());
			out.add(new Line("v" + r.version(), current ? 4 : 1));
			if (!r.date().isEmpty()) out.add(new Line(r.date(), 2));
			String body = r.changelog();
			for (String raw : body.split("\n")) {
				String line = raw.strip();
				if (line.isEmpty() || line.matches("(?i)^[-*]\\s*bump version.*")) continue;
				if (line.startsWith("#")) line = line.replaceFirst("^#+\\s*", "");
				if (line.startsWith("- ") || line.startsWith("* ")) line = "• " + line.substring(2);
				line = line.replace("**", "").replace("`", "");
				for (String l : ui.wrap(line, w)) out.add(new Line(l, 0));
			}
		}
		lines = out;
	}

	private int lineH(Line l) {
		return l.kind() == 3 ? 8 : l.kind() == 1 || l.kind() == 4 ? 14 : 11;
	}

	private int contentHeight() {
		int h = 0;
		for (Line l : lines) h += lineH(l);
		return h;
	}

	private int maxScroll() {
		return Math.max(0, contentHeight() - (ph - HEADER - 12));
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		if (parent != null) {
			try {
				parent.extractRenderState(graphics, -1, -1, delta);
			} catch (RuntimeException ignored) {
				// Drawing the screen behind is only cosmetic.
			}
		}
		ui.g = graphics;
		ui.theme = Theme.current(AutoDonutConfig.get());
		ui.alpha = open.get();
		ui.fill(0, 0, width, height, ui.theme.scrim());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		long now = System.nanoTime();
		ui.dt = Math.min(0.1f, (now - lastFrame) / 1_000_000_000f);
		lastFrame = now;
		ui.g = graphics;
		ui.font = font;
		ui.theme = Theme.current(AutoDonutConfig.get());
		float o = Anim.easeInOut(open.update(ui.dt));
		ui.alpha = o;
		Theme t = ui.theme;
		buildLines();

		graphics.pose().pushMatrix();
		graphics.pose().translate(0, (1f - o) * 8f);
		for (int i = 3; i >= 1; i--) {
			ui.round(px - i * 2, py - i * 2 + 2, pw + i * 4, ph + i * 4, 6 + i * 2, t.shadow() & 0x22FFFFFF);
		}
		ui.card(px, py, pw, ph, 4, t.panel(), t.border());
		ui.round(px + 1, py + 1, pw - 2, HEADER, 3, t.sidebar());
		ui.fill(px + 1, py + HEADER, px + pw - 1, py + HEADER + 1, t.border());
		ui.logo(px + 7, py + 5, 16);
		ui.bold("Changelog", px + 28, py + 9, t.text());
		ui.text("v" + AutoDonutClient.version(), px + 34 + ui.boldWidth("Changelog"), py + 9, t.textMuted());

		int cx = px + pw - 22, cy = py + 5;
		boolean overClose = mouseX >= cx && mouseX < cx + 16 && mouseY >= cy && mouseY < cy + 16;
		if (overClose && closeHover.target() < 0.5f) UiSounds.hover();
		closeHover.set(overClose ? 1 : 0);
		closeHover.update(ui.dt);
		ui.round(cx, cy, 16, 16, 3, Anim.lerpColor(t.sidebar(), t.surfaceHover(), closeHover.get()));
		int xc = Anim.lerpColor(t.textMuted(), t.text(), closeHover.get());
		for (int i = 0; i < 6; i++) {
			ui.fill(cx + 5 + i, cy + 5 + i, cx + 6 + i, cy + 6 + i, xc);
			ui.fill(cx + 10 - i, cy + 5 + i, cx + 11 - i, cy + 6 + i, xc);
		}

		int top = py + HEADER + 6;
		int bottom = py + ph - 6;
		if (lines.isEmpty()) {
			String msg = switch (Updater.state()) {
				case CHECKING -> "Loading the changelog...";
				case FAILED -> "Couldn't load the changelog.";
				default -> "No versions published yet.";
			};
			ui.textCentered(msg, px + pw / 2, top + 20, t.textMuted());
		} else {
			scrollTarget = Math.max(0, Math.min(maxScroll(), scrollTarget));
			scroll.set(scrollTarget);
			int off = Math.round(scroll.update(ui.dt));
			ui.scissor(px + 1, top, px + pw - 1, bottom);
			int y = top + 2 - off;
			for (Line l : lines) {
				int h = lineH(l);
				if (y + h >= top && y <= bottom) {
					switch (l.kind()) {
						case 1, 4 -> {
							ui.fill(px + 10, y, px + 12, y + 10, t.accent());
							ui.bold(l.text(), px + 16, y + 1, t.text());
							if (l.kind() == 4) ui.text("(installed)", px + 22 + ui.boldWidth(l.text()), y + 1, t.textMuted());
						}
						case 2 -> ui.text(l.text(), px + 16, y, t.textMuted());
						case 0 -> ui.text(l.text(), px + 16, y, t.text());
						default -> { }
					}
				}
				y += h;
			}
			ui.endScissor();
			int max = maxScroll();
			if (max > 0) {
				int trackH = bottom - top;
				int thumbH = Math.max(12, trackH * trackH / (trackH + max));
				int thumbY = top + Math.round((trackH - thumbH) * (off / (float) max));
				ui.round(px + pw - 6, thumbY, 2, thumbH, 1, t.border());
			}
		}
		graphics.pose().popMatrix();
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		scrollTarget = Math.max(0, Math.min(maxScroll(), scrollTarget - (float) scrollY * 22));
		return true;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x(), my = event.y();
		int cx = px + pw - 22, cy = py + 5;
		boolean inClose = mx >= cx && mx < cx + 16 && my >= cy && my < cy + 16;
		boolean outside = mx < px || mx >= px + pw || my < py || my >= py + ph;
		if (inClose || outside) {
			onClose();
			return true;
		}
		return true;
	}

	@Override
	public void onClose() {
		UiSounds.close();
		minecraft.gui.setScreen(parent);
	}
}
