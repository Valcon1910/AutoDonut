package com.autodonut.client.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Items the user wants listed automatically, plus the price and quantity condition.
 * Each entry is an item id ("minecraft:diamond") or an item tag starting with '#'
 * ("#c:foods", or just "#foods" to match that tag in any namespace).
 */
public class AuctionRule {
	public List<String> items = new ArrayList<>();
	/** Single item from older configs; moved into {@link #items} on load. */
	public String itemId = "";
	public String priceText = "";
	public boolean pricePerItem = false;
	public QuantityMode mode = QuantityMode.EXACTLY;
	/** Used by {@link QuantityMode#EXACTLY}. */
	public int amount = 64;
	/** Used by {@link QuantityMode#CUSTOM}. */
	public int min = 1;
	public int max = 64;
	public boolean enabled = true;

	/** Parsed price, or -1 when the price text is missing or invalid. */
	public long price() {
		return PriceFormat.parse(priceText);
	}

	public boolean hasItems() {
		return !items.isEmpty();
	}

	public boolean isComplete() {
		return hasItems() && price() > 0;
	}

	/** Whether one entry (item id or #tag) matches an item with the given id and tags. */
	public static boolean entryMatches(String entry, String stackItemId, Collection<String> stackTags) {
		if (!entry.startsWith("#")) return entry.equals(stackItemId);
		String tag = entry.substring(1);
		if (tag.contains(":")) return stackTags.contains(tag);
		for (String t : stackTags) {
			if (t.endsWith(":" + tag)) return true;
		}
		return false;
	}

	public boolean itemMatches(String stackItemId, Collection<String> stackTags) {
		for (String entry : items) {
			if (entryMatches(entry, stackItemId, stackTags)) return true;
		}
		return false;
	}

	public boolean countMatches(int count) {
		return switch (mode) {
			case EXACTLY -> count == amount;
			case CUSTOM -> count >= min && count <= max;
		};
	}

	public boolean matches(String stackItemId, Collection<String> stackTags, int count) {
		return enabled && isComplete() && itemMatches(stackItemId, stackTags) && countMatches(count);
	}

	public boolean matches(String stackItemId, int count) {
		return matches(stackItemId, Set.of(), count);
	}

	/**
	 * How many items to split off an oversized stack, or 0. A stack bigger than "Exact N"
	 * (or the Custom maximum) gives N (or the maximum).
	 */
	public int splitAmount(String stackItemId, Collection<String> stackTags, int count) {
		if (!enabled || !isComplete() || !itemMatches(stackItemId, stackTags)) return 0;
		int want = mode == QuantityMode.EXACTLY ? amount : max;
		return count > want ? want : 0;
	}

	public int splitAmount(String stackItemId, int count) {
		return splitAmount(stackItemId, Set.of(), count);
	}

	/** Total price for a stack of {@code count} items. */
	public long totalPrice(int count) {
		long p = price();
		return pricePerItem ? p * count : p;
	}

	/** Short human description of the quantity rule, e.g. "Exact 64" or "16 to 64". */
	public String quantityText() {
		return switch (mode) {
			case EXACTLY -> "Exact " + amount;
			case CUSTOM -> min == max ? "Exact " + min : min + " to " + max;
		};
	}

	public void sanitize() {
		if (items == null) items = new ArrayList<>();
		if (itemId != null && !itemId.isEmpty() && !items.contains(itemId)) items.add(0, itemId);
		itemId = "";
		items.removeIf(e -> e == null || e.isBlank());
		if (priceText == null) priceText = "";
		if (mode == null) mode = QuantityMode.EXACTLY;
		// Prices are always for the whole listed stack.
		pricePerItem = false;
		amount = Math.clamp(amount, 1, 64);
		min = Math.clamp(min, 1, 64);
		max = Math.clamp(max, 1, 64);
		if (min > max) {
			int t = min;
			min = max;
			max = t;
		}
	}
}
