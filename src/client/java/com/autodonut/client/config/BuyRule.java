package com.autodonut.client.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Items Auto Buy looks for on /ah, with the budget a listing must fit and how often to look.
 * Entries work like {@link AuctionRule}'s: item ids or #tags; the rule matches any of them.
 */
public class BuyRule {
	public enum BudgetMode {
		/** {@link #budgetExact}: "1k" (exactly), "<1k", "<=1k", ">500", ">=500". */
		EXACT("Exact"),
		/** Anything priced at or below {@link #budgetMax}. */
		MAX("Max");

		private final String label;

		BudgetMode(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}
	}

	public enum SearchMode {
		/** "/ah <item name>": only matching listings show. */
		SEARCH("Search"),
		/** Plain "/ah", paging through listings. */
		BROWSE("Browse");

		private final String label;

		SearchMode(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}
	}

	/** How often /ah is checked, as a random interval between {@code minMs} and {@code maxMs}. */
	public enum Speed {
		SAFE("Safe", 15000, 30000),
		SLOW("Slow", 5000, 10000),
		BALANCED("Balanced", 2000, 5000),
		FAST("Fast", 1000, 2000),
		AGGRESSIVE("Aggressive", 500, 1000);

		private final String label;
		public final long minMs;
		public final long maxMs;

		Speed(String label, long minMs, long maxMs) {
			this.label = label;
			this.minMs = minMs;
			this.maxMs = maxMs;
		}

		public String label() {
			return label;
		}
	}

	/** Steps of the Max budget slider: 1, 1.5, 2, 2.5, 3, 4, 5, 6, 7.5 per power of ten, $1 to $100M. */
	public static final long[] MAX_STEPS;

	static {
		double[] mantissas = {1, 1.5, 2, 2.5, 3, 4, 5, 6, 7.5};
		List<Long> steps = new ArrayList<>();
		long scale = 1;
		for (int decade = 0; decade < 8; decade++) {
			for (double m : mantissas) {
				long v = Math.round(m * scale);
				if (steps.isEmpty() || steps.get(steps.size() - 1) != v) steps.add(v);
			}
			scale *= 10;
		}
		steps.add(100_000_000L);
		MAX_STEPS = steps.stream().mapToLong(Long::longValue).toArray();
	}

	public List<String> items = new ArrayList<>();
	public boolean enabled = true;
	public BudgetMode budgetMode = BudgetMode.MAX;
	public String budgetExact = "";
	/** Listing stack size filter: "64" (exactly), "<16", "<=16", ">32", ">=32". Empty = any quantity. */
	public String quantity = "";
	public long budgetMax = 1000;
	public SearchMode searchMode = SearchMode.SEARCH;
	/** Seconds to wait after a purchase before the next check. */
	public int delayMin = 5;
	public int delayMax = 15;
	public boolean pauseAfterEnabled = false;
	public int pauseAfterCount = 10;
	/** Whether the Auto Buy pause key pauses this item. */
	public boolean pauseKeyEnabled = true;
	public Speed speed = Speed.BALANCED;
	/** Purchases since the counter was last reset (not saved). */
	public transient int purchases;

	public boolean hasItems() {
		return !items.isEmpty();
	}

	/** Whether the budget is set up well enough to buy anything. */
	public boolean budgetValid() {
		return budgetMode == BudgetMode.MAX ? budgetMax > 0 : parseExact(budgetExact) != null;
	}

	public boolean isComplete() {
		return hasItems() && budgetValid();
	}

	public boolean itemMatches(String stackItemId, Collection<String> stackTags) {
		for (String entry : items) {
			if (AuctionRule.entryMatches(entry, stackItemId, stackTags)) return true;
		}
		return false;
	}

	/** Whether a listing with this whole-listing price fits the budget. */
	public boolean allows(long price) {
		if (price <= 0) return false;
		if (budgetMode == BudgetMode.MAX) return price <= budgetMax;
		long[] exact = parseExact(budgetExact);
		if (exact == null) return false;
		return compare(exact, price);
	}

	/** Whether the quantity filter is empty or valid. */
	public boolean quantityValid() {
		return quantity.isBlank() || parseQuantity(quantity) != null;
	}

	/** Whether a listing of {@code count} items passes the quantity filter (empty = any). */
	public boolean allowsCount(int count) {
		if (quantity.isBlank()) return true;
		long[] q = parseQuantity(quantity);
		return q != null && compare(q, count);
	}

	/** Short description of the quantity filter, e.g. "Exactly 64" or "Below 16", or "" when empty. */
	public String quantityText() {
		long[] q = quantity.isBlank() ? null : parseQuantity(quantity);
		return q == null ? "" : comparisonText(q, Long.toString(q[1])) + (q[1] == 1 && q[0] == 0 ? " item" : " items");
	}

	/** Applies a parsed {comparison, amount} (see {@link #parseComparison}) to a value. */
	public static boolean compare(long[] cmp, long value) {
		long v = cmp[1];
		return switch ((int) cmp[0]) {
			case -2 -> value < v;
			case -1 -> value <= v;
			case 1 -> value >= v;
			case 2 -> value > v;
			default -> value == v;
		};
	}

	private static String comparisonText(long[] cmp, String amount) {
		return switch ((int) cmp[0]) {
			case -2 -> "Below " + amount;
			case -1 -> "Up to " + amount;
			case 1 -> amount + " or more";
			case 2 -> "Above " + amount;
			default -> "Exactly " + amount;
		};
	}

	/** Parses a quantity filter like "64", "<16" or ">=32"; null when invalid. */
	public static long[] parseQuantity(String text) {
		return parseComparison(text, s -> {
			if (s.isEmpty() || s.length() > 5 || !s.chars().allMatch(Character::isDigit)) return -1;
			return Long.parseLong(s);
		});
	}

	/** Characters allowed while typing a quantity filter. */
	public static boolean isQuantityChar(char c) {
		return Character.isDigit(c) || c == '<' || c == '>' || c == '=';
	}

	/** Whether the purchase limit ("pause after N") has been reached. */
	public boolean limitReached() {
		return pauseAfterEnabled && purchases >= pauseAfterCount;
	}

	/**
	 * Parses an Exact budget: {comparison, amount} with comparison -2 = "<", -1 = "<=", 0 = exactly,
	 * 1 = ">=", 2 = ">". Returns null when the text isn't a valid budget.
	 */
	public static long[] parseExact(String text) {
		return parseComparison(text, PriceFormat::parse);
	}

	/**
	 * Splits an optional leading "<", "<=", ">" or ">=" off the text and parses the rest with
	 * {@code amount}: {comparison, amount} as in {@link #parseExact}, or null when invalid.
	 */
	public static long[] parseComparison(String text, java.util.function.ToLongFunction<String> amount) {
		if (text == null) return null;
		String s = text.trim();
		int cmp = 0;
		if (s.startsWith("<=")) {
			cmp = -1;
			s = s.substring(2);
		} else if (s.startsWith(">=")) {
			cmp = 1;
			s = s.substring(2);
		} else if (s.startsWith("<")) {
			cmp = -2;
			s = s.substring(1);
		} else if (s.startsWith(">")) {
			cmp = 2;
			s = s.substring(1);
		}
		long v = amount.applyAsLong(s.trim());
		return v > 0 ? new long[]{cmp, v} : null;
	}

	/** Short description of the budget, e.g. "Max $1.5K", "Below $1K" or "Exactly $500". */
	public String budgetText() {
		if (budgetMode == BudgetMode.MAX) return "Max $" + PriceFormat.format(budgetMax);
		long[] exact = parseExact(budgetExact);
		if (exact == null) return "No budget";
		return comparisonText(exact, "$" + PriceFormat.format(exact[1]));
	}

	/** Index of the Max slider step closest to {@link #budgetMax}. */
	public int maxStepIndex() {
		int best = 0;
		for (int i = 0; i < MAX_STEPS.length; i++) {
			if (Math.abs(MAX_STEPS[i] - budgetMax) < Math.abs(MAX_STEPS[best] - budgetMax)) best = i;
		}
		return best;
	}

	/** Characters allowed while typing an Exact budget. */
	public static boolean isBudgetChar(char c) {
		return PriceFormat.isPriceChar(c) || c == '<' || c == '>' || c == '=';
	}

	public void sanitize() {
		if (items == null) items = new ArrayList<>();
		items.removeIf(e -> e == null || e.isBlank());
		if (budgetMode == null) budgetMode = BudgetMode.MAX;
		if (budgetExact == null) budgetExact = "";
		if (quantity == null) quantity = "";
		budgetExact = budgetExact.toLowerCase(Locale.ROOT);
		if (budgetMax <= 0) budgetMax = 1000;
		budgetMax = Math.min(budgetMax, MAX_STEPS[MAX_STEPS.length - 1]);
		if (searchMode == null) searchMode = SearchMode.SEARCH;
		if (speed == null) speed = Speed.BALANCED;
		delayMin = Math.clamp(delayMin, 0, 60);
		delayMax = Math.clamp(delayMax, 0, 60);
		if (delayMin > delayMax) {
			int t = delayMin;
			delayMin = delayMax;
			delayMax = t;
		}
		pauseAfterCount = Math.clamp(pauseAfterCount, 1, 64);
	}
}
