package com.autodonut.client.config;

/** One item the user wants listed automatically, plus its price and quantity condition. */
public class AuctionRule {
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

	public boolean isComplete() {
		return !itemId.isEmpty() && price() > 0;
	}

	public boolean countMatches(int count) {
		return switch (mode) {
			case EXACTLY -> count == amount;
			case CUSTOM -> count >= min && count <= max;
		};
	}

	public boolean matches(String stackItemId, int count) {
		return enabled && isComplete() && itemId.equals(stackItemId) && countMatches(count);
	}

	/** Total price for a stack of {@code count} items. */
	public long totalPrice(int count) {
		long p = price();
		return pricePerItem ? p * count : p;
	}

	/** Short human description of the quantity rule, e.g. "Exactly 64" or "16 to 64". */
	public String quantityText() {
		return switch (mode) {
			case EXACTLY -> "Exactly " + amount;
			case CUSTOM -> min == max ? "Exactly " + min : min + " to " + max;
		};
	}

	public void sanitize() {
		if (itemId == null) itemId = "";
		if (priceText == null) priceText = "";
		if (mode == null) mode = QuantityMode.EXACTLY;
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
