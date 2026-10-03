package com.autodonut.client.ui;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import com.autodonut.client.auction.AutoAuctionController;
import com.autodonut.client.config.AutoDonutConfig;
import com.autodonut.client.config.PriceFormat;

/**
 * Quick Sell price prompt (R): "Type price" for one stack, then Continue lists it in the
 * background through Auto Auction's listing path (including auto-confirm).
 */
public class QuickSellScreen extends Screen {
	private final int inventorySlot;
	private final ItemStack stack;
	private final Ui ui = new Ui();
	private final long openedAt = System.nanoTime();
	private EditBox price;
	private Button continueButton;
	private int px, py, pw, ph;

	public QuickSellScreen(int inventorySlot, ItemStack stack) {
		super(Component.literal("Type price"));
		this.inventorySlot = inventorySlot;
		this.stack = stack.copy();
	}

	@Override
	protected void init() {
		ui.font = font;
		ui.theme = Theme.current(AutoDonutConfig.get());
		pw = Math.min(220, width - 20);
		ph = 132;
		px = (width - pw) / 2;
		py = (height - ph) / 2;

		price = new EditBox(font, px + 12, py + 66, pw - 24, 18, price, Component.literal("Price"));
		price.setMaxLength(16);
		price.setResponder(t -> updateButton());
		addRenderableWidget(price);
		setInitialFocus(price);

		continueButton = addRenderableWidget(Button.builder(Component.literal("Continue"), b -> submit())
				.bounds(px + 12, py + 92, (pw - 30) / 2, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
				.bounds(px + 18 + (pw - 30) / 2, py + 92, (pw - 30) / 2, 20).build());
		updateButton();
		UiSounds.open();
	}

	private long parsed() {
		return price == null ? -1 : PriceFormat.parse(price.getValue());
	}

	private void updateButton() {
		if (continueButton != null) continueButton.active = parsed() > 0;
	}

	private void submit() {
		long total = parsed();
		if (total <= 0) return;
		UiSounds.click();
		onClose();
		AutoAuctionController.get().quickList(inventorySlot, stack, total);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == InputConstants.KEY_RETURN && parsed() > 0) {
			submit();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		ui.g = graphics;
		ui.alpha = Anim.easeOutCubic((System.nanoTime() - openedAt) / 1_000_000f / 180f);
		ui.fill(0, 0, width, height, ui.theme.scrim());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		ui.g = graphics;
		ui.font = font;
		ui.alpha = Anim.easeOutCubic((System.nanoTime() - openedAt) / 1_000_000f / 180f);
		Theme t = ui.theme;
		ui.card(px, py, pw, ph, 4, t.panel(), t.border());
		ui.fill(px + 1, py + 1, px + pw - 1, py + 3, t.accent());
		ui.logo(px + 8, py + 9, 12);
		ui.text("Type price", px + 24, py + 11, t.text());

		ui.round(px + 12, py + 28, 22, 22, 3, t.surface());
		ui.item(stack, px + 15, py + 31);
		ui.text(ui.trim(stack.getHoverName().getString(), pw - 60), px + 40, py + 30, t.text());
		ui.text("x" + stack.getCount() + "  -  whole stack", px + 40, py + 41, t.textMuted());

		long total = parsed();
		String hint = total > 0 ? "Lists for $" + PriceFormat.format(total) : "e.g. 500, 1.5k or 2m";
		ui.text("Price", px + 12, py + 56, t.textMuted());
		ui.textRight(hint, px + pw - 12, py + 56, total > 0 ? t.success() : t.textMuted());
		super.extractRenderState(graphics, mouseX, mouseY, delta);
	}
}
