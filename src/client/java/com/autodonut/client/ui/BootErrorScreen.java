package com.autodonut.client.ui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import com.autodonut.client.BootSequence;
import com.autodonut.client.config.AutoDonutConfig;
import com.autodonut.client.ui.widget.UiButton;

/** Explains which boot steps failed, what probably caused it, and lets the player carry on. */
public class BootErrorScreen extends Screen {
	private final List<BootSequence.Failure> failures;
	private final Ui ui = new Ui();
	private final long openedAt = System.nanoTime();
	private long lastFrame = System.nanoTime();
	private UiButton continueButton;
	private int px, py, pw, ph;

	public BootErrorScreen(List<BootSequence.Failure> failures) {
		super(Component.literal("AutoDonut couldn't finish starting"));
		this.failures = failures;
	}

	private List<String> lines() {
		List<String> out = new ArrayList<>();
		for (BootSequence.Failure f : failures) {
			out.add("✘ " + f.step());
			out.addAll(ui.wrap("Why: " + f.reason(), pw - 28));
			out.addAll(ui.wrap("Likely cause: " + f.cause(), pw - 28));
			out.add("");
		}
		out.addAll(ui.wrap("AutoDonut will keep working, but the parts above may not behave correctly. "
				+ "Check the log (logs/latest.log) for details.", pw - 28));
		return out;
	}

	@Override
	protected void init() {
		ui.font = font;
		ui.theme = Theme.current(AutoDonutConfig.get());
		pw = Math.min(300, width - 20);
		int textH = lines().size() * 10;
		ph = Math.min(height - 20, 40 + textH + 30);
		px = (width - pw) / 2;
		py = (height - ph) / 2;
		continueButton = new UiButton("Continue anyway", UiButton.Style.PRIMARY, this::onClose);
		continueButton.bounds(px + pw - 104, py + ph - 26, 96, 18);
		UiSounds.click();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		ui.g = graphics;
		ui.alpha = Anim.easeOutCubic((System.nanoTime() - openedAt) / 1_000_000f / 200f);
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
		ui.alpha = Anim.easeOutCubic((now - openedAt) / 1_000_000f / 200f);
		Theme t = ui.theme;

		ui.card(px, py, pw, ph, 4, t.panel(), t.border());
		ui.fill(px + 1, py + 1, px + 3, py + ph - 1, t.danger());
		ui.logo(px + 10, py + 9, 14);
		ui.text("AutoDonut couldn't finish starting", px + 30, py + 10, t.text());
		ui.text(failures.size() + (failures.size() == 1 ? " step failed" : " steps failed"), px + 30, py + 21, t.textMuted());

		int y = py + 38;
		for (String line : lines()) {
			if (y > py + ph - 36) break;
			int color = line.startsWith("✘") ? t.danger() : line.startsWith("Likely cause") ? Ui.WARNING : t.textMuted();
			ui.text(line, px + 14, y, color);
			y += 10;
		}
		continueButton.render(ui, mouseX, mouseY);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		return continueButton.mouseClicked(event.x(), event.y()) || super.mouseClicked(event, doubleClick);
	}
}
