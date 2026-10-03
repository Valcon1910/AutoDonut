package com.autodonut.client.config;

/** How a stack's size is checked against a rule. */
public enum QuantityMode {
	/** The stack must have exactly {@code amount} items. */
	EXACTLY("Exactly"),
	/** The stack size must be between {@code min} and {@code max}, inclusive. */
	CUSTOM("Custom");

	private final String label;

	QuantityMode(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}
}
