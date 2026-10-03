package com.autodonut.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

/** Subtle interface sounds, all based on the vanilla button click at different pitches. */
public final class UiSounds {
	private static long lastHover;
	private static long lastSlide;

	private UiSounds() {
	}

	private static void play(float pitch, float volume) {
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), pitch, volume));
	}

	/**
	 * Soft wooden tick played each time a slider moves to a new value. The pitch rises with
	 * the value (0..1), so sweeping the bar sounds like a scale.
	 */
	public static void slide(float fraction) {
		long now = System.currentTimeMillis();
		if (now - lastSlide < 28) return;
		lastSlide = now;
		float pitch = 0.75f + Math.max(0f, Math.min(1f, fraction)) * 1.0f;
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_HAT.value(), pitch, 0.18f));
	}

	/** Very quiet high tick when the mouse moves onto something clickable. */
	public static void hover() {
		long now = System.currentTimeMillis();
		if (now - lastHover < 45) return;
		lastHover = now;
		play(2.0f, 0.06f);
	}

	public static void click() {
		play(1.0f, 0.35f);
	}

	/** Rising pitch when switching on, falling when switching off. */
	public static void toggle(boolean on) {
		play(on ? 1.35f : 0.85f, 0.35f);
	}
}
