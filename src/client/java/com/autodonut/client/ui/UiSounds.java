package com.autodonut.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

import com.autodonut.client.Compat;

/** Subtle interface sounds, all based on the vanilla button click at different pitches. */
public final class UiSounds {
	private static long lastHover;
	private static long lastSlide;

	private UiSounds() {
	}

	private static void play(float pitch, float volume) {
		play(SoundEvents.UI_BUTTON_CLICK.value(), pitch, volume);
	}

	/** Every AutoDonut sound goes through here; all are muted in Streamer Mode / while a recording mod is present. */
	private static void play(SoundEvent sound, float pitch, float volume) {
		if (Compat.streamerMode()) return;
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
	}

	/** Soft two-note chime when the panel opens. */
	public static void open() {
		play(SoundEvents.NOTE_BLOCK_CHIME.value(), 1.5f, 0.22f);
		play(SoundEvents.NOTE_BLOCK_CHIME.value(), 2.0f, 0.14f);
	}

	/** Lower single chime when the panel closes. */
	public static void close() {
		play(SoundEvents.NOTE_BLOCK_CHIME.value(), 1.2f, 0.16f);
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
		play(SoundEvents.NOTE_BLOCK_HAT.value(), pitch, 0.18f);
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
