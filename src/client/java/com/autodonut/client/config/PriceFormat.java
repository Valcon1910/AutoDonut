package com.autodonut.client.config;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/** Parses and formats prices like "1.5k", "250,000", "$2m". */
public final class PriceFormat {
	private static final long MAX = 1_000_000_000_000L;

	private PriceFormat() {
	}

	/** Returns the price in whole coins, or -1 if the text is not a valid positive price. */
	public static long parse(String text) {
		if (text == null) return -1;
		String s = text.trim().toLowerCase(Locale.ROOT).replace(",", "").replace("_", "").replace("$", "").replace(" ", "");
		if (s.isEmpty()) return -1;

		long multiplier = 1;
		char last = s.charAt(s.length() - 1);
		switch (last) {
			case 'k' -> multiplier = 1_000L;
			case 'm' -> multiplier = 1_000_000L;
			case 'b' -> multiplier = 1_000_000_000L;
			default -> { }
		}
		if (multiplier != 1) s = s.substring(0, s.length() - 1);
		if (s.isEmpty()) return -1;

		try {
			BigDecimal value = new BigDecimal(s).multiply(BigDecimal.valueOf(multiplier));
			if (value.signum() <= 0 || value.compareTo(BigDecimal.valueOf(MAX)) > 0) return -1;
			long rounded = value.setScale(0, RoundingMode.HALF_UP).longValueExact();
			return rounded > 0 ? rounded : -1;
		} catch (NumberFormatException | ArithmeticException e) {
			return -1;
		}
	}

	/** Short human form: 1500 -> "1.5K", 2000000 -> "2M". */
	public static String format(long value) {
		if (value < 1_000) return Long.toString(value);
		String[] suffixes = {"K", "M", "B", "T"};
		double v = value;
		int i = -1;
		while (v >= 1_000 && i < suffixes.length - 1) {
			v /= 1_000;
			i++;
		}
		// 999,999 would otherwise round to "1000K".
		if (v >= 999.5 && i < suffixes.length - 1) {
			v /= 1_000;
			i++;
		}
		String num = v >= 100 ? String.format(Locale.ROOT, "%.0f", v)
				: v >= 10 ? String.format(Locale.ROOT, "%.1f", v)
				: String.format(Locale.ROOT, "%.2f", v);
		if (num.contains(".")) num = num.replaceAll("0+$", "").replaceAll("\\.$", "");
		return num + suffixes[i];
	}

	/** Characters allowed while typing a price. */
	public static boolean isPriceChar(char c) {
		return Character.isDigit(c) || c == '.' || c == ',' || c == 'k' || c == 'K' || c == 'm' || c == 'M' || c == 'b' || c == 'B';
	}
}
