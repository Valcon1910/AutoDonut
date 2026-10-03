package com.autodonut.client.config;

/** One item the user wants listed automatically, plus its price and quantity condition. */
public class AuctionRule {
	public String itemId = "";
	public String priceText = "";
	public boolean pricePerItem = false;
	public QuantityMode mode = QuantityMode.EXACTLY;
	public int amount = 64;
	public boolean enabled = true;

	/** Parsed price, or -1 when the price text is missing or invalid. */
	public long price() {
		return PriceFormat.parse(priceText);
	}

	public boolean isComplete() {
		return !itemId.isEmpty() && price() > 0;
	}

	public boolean matches(String stackItemId, int count) {
		return enabled && isComplete() && itemId.equals(stackItemId) && mode.test(count, amount);
	}

	/** Total price for a stack of {@code count} items. */
	public long totalPrice(int count) {
		long p = price();
		return pricePerItem ? p * count : p;
	}
}
