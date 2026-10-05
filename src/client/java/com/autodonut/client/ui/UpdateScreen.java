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
import com.autodonut.client.ui.widget.ToggleSwitch;
import com.autodonut.client.ui.widget.UiButton;
import com.autodonut.client.ui.widget.Widget;

/** Small update window (title screen download icon): target version, install button and update settings. */
public class UpdateScreen extends Screen {
	private static final int HEADER = 26;
	private final Screen parent;
	private final Ui ui = new Ui();
	private final Anim open = new Anim(0, 12);
	private final Anim closeHover = new Anim(0, 16);
	private final List<Widget> widgets = new ArrayList<>();
	private long lastFrame = System.nanoTime();
	private Updater.State builtFor;
	private int px, py, pw, ph;

	public UpdateScreen(Screen parent) {
		super(Component.literal("Update"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		ui.font = font;
		pw = Math.min(250, width - 32);
		ph = Math.min(156, height - 32);
		px = (width - pw) / 2;
		py = (height - ph) / 2;
		open.set(1);
		UiSounds.open();
		build();
	}

	private void build() {
		builtFor = Updater.state();
		widgets.clear();
		AutoDonutConfig cfg = AutoDonutConfig.get();
		int rowY = py + HEADER + 50;
		widgets.add(new ToggleSwitch(() -> cfg.checkUpdates, v -> {
			cfg.checkUpdates = v;
			AutoDonutConfig.save();
		}).bounds(px + pw - 12 - ToggleSwitch.WIDTH, rowY + 2, ToggleSwitch.WIDTH, ToggleSwitch.HEIGHT));
		widgets.add(new ToggleSwitch(() -> cfg.autoUpdate, v -> {
			cfg.autoUpdate = v;
			AutoDonutConfig.save();
			if (v && Updater.state() == Updater.State.AVAILABLE) Updater.install();
		}).bounds(px + pw - 12 - ToggleSwitch.WIDTH, rowY + 28, ToggleSwitch.WIDTH, ToggleSwitch.HEIGHT));

		String label = switch (builtFor) {
			case AVAILABLE -> "Update now";
			case FAILED -> "Try again";
			default -> null;
		};
		if (label != null) {
			int bw = Math.max(90, UiButton.widthFor(ui, label));
			widgets.add(new UiButton(label, UiButton.Style.PRIMARY, () -> {
				if (Updater.state() == Updater.State.AVAILABLE) {
					Updater.install();
				} else {
					Updater.check();
				}
			}).bounds(px + (pw - bw) / 2, py + ph - 28, bw, 18));
		}
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
		if (Updater.state() != builtFor) build();
		long now = System.nanoTime();
		ui.dt = Math.min(0.1f, (now - lastFrame) / 1_000_000_000f);
		lastFrame = now;
		ui.g = graphics;
		ui.font = font;
		ui.theme = Theme.current(AutoDonutConfig.get());
		float o = Anim.easeInOut(open.update(ui.dt));
		ui.alpha = o;
		Theme t = ui.theme;

		graphics.pose().pushMatrix();
		graphics.pose().translate(0, (1f - o) * 8f);
		for (int i = 3; i >= 1; i--) {
			ui.round(px - i * 2, py - i * 2 + 2, pw + i * 4, ph + i * 4, 6 + i * 2, t.shadow() & 0x22FFFFFF);
		}
		ui.card(px, py, pw, ph, 4, t.panel(), t.border());
		ui.round(px + 1, py + 1, pw - 2, HEADER, 3, t.sidebar());
		ui.fill(px + 1, py + HEADER, px + pw - 1, py + HEADER + 1, t.border());
		ui.logo(px + 7, py + 5, 16);
		ui.bold(Updater.updateAvailable() ? "Update available" : "Updates", px + 28, py + 9, t.text());

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

		// Version line: installed -> new
		Updater.Release latest = Updater.latest();
		int top = py + HEADER + 10;
		String from = "v" + AutoDonutClient.version();
		if (latest != null) {
			String to = "v" + latest.version();
			String arrow = "  ->  ";
			int total = ui.width(from) + ui.width(arrow) + ui.boldWidth(to);
			int vx = px + (pw - total) / 2;
			ui.text(from, vx, top, t.textMuted());
			ui.text(arrow, vx + ui.width(from), top, t.textMuted());
			ui.bold(to, vx + ui.width(from) + ui.width(arrow), top, t.accent());
		} else {
			ui.textCentered(from, px + pw / 2, top, t.textMuted());
		}
		var u = Updater.state();
		String status = switch (u) {
			case IDLE -> "Not checked yet";
			case CHECKING -> "Checking for updates...";
			case UP_TO_DATE -> "You're on the latest version";
			case AVAILABLE -> "Ready to download";
			case DOWNLOADING -> "Downloading  " + Math.round(Updater.progress() * 100) + "%";
			case READY -> "Installed. Restart the game to use it";
			case FAILED -> Updater.error();
		};
		int sc = u == Updater.State.FAILED ? t.danger() : u == Updater.State.READY ? t.success() : t.textMuted();
		ui.textCentered(ui.trim(status, pw - 20), px + pw / 2, top + 14, sc);
		if (u == Updater.State.DOWNLOADING) ui.meter(px + 30, top + 26, pw - 60, Updater.progress(), t.accent());

		int rowY = py + HEADER + 50;
		ui.fill(px + 10, rowY - 6, px + pw - 10, rowY - 5, t.border());
		ui.text("Check for updates", px + 12, rowY, t.text());
		ui.text("Every time the game starts", px + 12, rowY + 10, t.textMuted());
		ui.text("Auto update", px + 12, rowY + 26, t.text());
		ui.text("Install new versions by itself", px + 12, rowY + 36, t.textMuted());

		for (Widget w : widgets) w.render(ui, mouseX, mouseY);
		graphics.pose().popMatrix();
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
		for (Widget w : new ArrayList<>(widgets)) {
			if (w.mouseClicked(mx, my)) return true;
		}
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		for (Widget w : widgets) w.mouseReleased(event.x(), event.y());
		return true;
	}

	@Override
	public void onClose() {
		UiSounds.close();
		minecraft.gui.setScreen(parent);
	}
}
