package com.autodonut.client.config;

/** How a stack's size is compared against a rule's amount. */
public enum QuantityMode {
	EXACTLY("Exactly"),
	LESS_THAN("Less than"),
	MORE_THAN("More than");

	private final String label;

	QuantityMode(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

	public boolean test(int count, int amount) {
		return switch (this) {
			case EXACTLY -> count == amount;
			case LESS_THAN -> count < amount;
			case MORE_THAN -> count > amount;
		};
	}
}
