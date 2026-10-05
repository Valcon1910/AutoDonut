package com.autodonut.client;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.loader.api.FabricLoader;

import com.autodonut.AutoDonut;
import com.autodonut.client.auction.AutoAuctionController;
import com.autodonut.client.auction.AutoBuyController;
import com.autodonut.client.config.AutoDonutConfig;
import com.autodonut.client.ui.AutoDonutScreen;
import com.autodonut.client.ui.BootOverlay;
import com.autodonut.client.ui.StatusHud;
import com.autodonut.client.ui.TitleButton;

public class AutoDonutClient implements ClientModInitializer {
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(id("main"));
	private static KeyMapping openKey;
	private static KeyMapping quickSellKey;
	private static KeyMapping skipKey;
	private static KeyMapping buyPauseKey;
	private static String version = "dev";

	/** True while a text box inside this screen has keyboard focus. */
	private static boolean isTyping(net.minecraft.client.gui.components.events.ContainerEventHandler parent) {
		var focused = parent.getFocused();
		for (int depth = 0; depth < 6 && focused != null; depth++) {
			if (focused instanceof net.minecraft.client.gui.components.EditBox box) return box.isFocused();
			if (!(focused instanceof net.minecraft.client.gui.components.events.ContainerEventHandler inner)) return false;
			focused = inner.getFocused();
		}
		return false;
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(AutoDonut.MOD_ID, path);
	}

	public static KeyMapping openKey() {
		return openKey;
	}

	/** The key currently bound to Quick Sell, as shown in Controls (e.g. "R"). */
	public static String quickSellKeyName() {
		return quickSellKey == null ? "R" : quickSellKey.getTranslatedKeyMessage().getString();
	}

	/** The key bound to "continue now" (default Y). */
	public static String skipKeyName() {
		return skipKey == null ? "Y" : skipKey.getTranslatedKeyMessage().getString();
	}

	/** The key that pauses / resumes Auto Buy (default J). */
	public static String buyPauseKeyName() {
		return buyPauseKey == null ? "J" : buyPauseKey.getTranslatedKeyMessage().getString();
	}

	public static String version() {
		return version;
	}

	@Override
	public void onInitializeClient() {
		LogFilter.install();
		AutoDonutConfig.load();
		version = FabricLoader.getInstance().getModContainer(AutoDonut.MOD_ID)
				.map(c -> c.getMetadata().getVersion().getFriendlyString())
				.orElse("dev");
		Updater.start();

		openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.autodonut.open",
				InputConstants.Type.KEYBOARD,
				InputConstants.KEY_K,
				CATEGORY
		));

		quickSellKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.autodonut.quick_sell",
				InputConstants.Type.KEYBOARD,
				InputConstants.KEY_R,
				CATEGORY
		));

		skipKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.autodonut.skip_wait",
				InputConstants.Type.KEYBOARD,
				InputConstants.KEY_Y,
				CATEGORY
		));

		buyPauseKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.autodonut.buy_pause",
				InputConstants.Type.KEYBOARD,
				InputConstants.KEY_J,
				CATEGORY
		));

		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (screen instanceof net.minecraft.client.gui.screens.TitleScreen) TitleButton.add(client, screen, w, h);
		});

		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
		// Inside inventories key mappings don't fire, so listen for R on every screen.
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) ->
				ScreenKeyboardEvents.afterKeyPress(screen).register((s, keyEvent) -> {
					// Typing into a text box (creative / recipe search, anvil, sign...) never triggers R or J.
					if (isTyping(s)) return;
					if (quickSellKey.matches(keyEvent) && QuickSell.allowedOn(s)) QuickSell.trigger(client);
					// The auction menu Auto Buy opened swallows key mappings, so J is caught here too.
					if (buyPauseKey.matches(keyEvent) && AutoBuyController.get().ownsScreen(s)) AutoBuyController.get().togglePause();
				}));
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			Lockdown.onSystemMessage(message);
			if (!overlay) BootOverlay.onSystemMessage(message);
			AutoAuctionController.get().onGameMessage(message, overlay);
			AutoBuyController.get().onGameMessage(message, overlay);
		});
		ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) -> Lockdown.onChatMessage(message));
		ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> {
			ServerContext.onDisconnect();
			BootOverlay.onDisconnect();
			AutoAuctionController.get().onDisconnect();
			AutoBuyController.get().onDisconnect();
		});
		HudElementRegistry.addLast(id("status"), StatusHud::extract);
		HudElementRegistry.addLast(id("boot"), BootOverlay::extract);
		ClientPlayConnectionEvents.JOIN.register((listener, sender, client) -> {
			ServerContext.onJoin(client.getSingleplayerServer() != null);
			com.autodonut.client.auction.ItemIndex.clearTags();
			AutoAuctionController.get().onJoin();
			AutoBuyController.get().onJoin();
			ServerProbe.reset();
			BootOverlay.onJoin();
		});
	}

	private void onTick(Minecraft client) {
		while (openKey.consumeClick()) {
			if (client.gui.screen() == null) {
				client.gui.setScreen(new AutoDonutScreen());
			}
		}
		while (quickSellKey.consumeClick()) {
			if (client.gui.screen() == null) QuickSell.trigger(client);
		}
		while (skipKey.consumeClick()) {
			if (client.gui.screen() == null && AutoDonutConfig.get().skipKeyEnabled) AutoAuctionController.get().skipWait();
		}
		while (buyPauseKey.consumeClick()) {
			if (client.gui.screen() == null) AutoBuyController.get().togglePause();
		}
		QuickSell.tick(client);
		ServerProbe.tick(client);
		if (client.player == null && !(client.gui.screen() instanceof net.minecraft.client.gui.screens.ConnectScreen)) {
			ServerContext.onNotInWorld();
		}
		AutoAuctionController.get().tick(client);
		AutoBuyController.get().tick(client);
		BootSequence.tick(client);
		BootOverlay.reportFailures(client);
	}
}
