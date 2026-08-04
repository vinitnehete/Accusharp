package com.accusharp.hrms.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Renders a rupee amount in words for the salary slip, using the Indian
 * numbering system (lakh / crore).
 */
public final class AmountInWords {

    private static final String[] UNITS = {
            "", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten",
            "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen",
            "Eighteen", "Nineteen"
    };

    private static final String[] TENS = {
            "", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"
    };

    private AmountInWords() {
    }

    public static String convert(BigDecimal amount) {
        if (amount == null) {
            return "Zero Rupees Only";
        }
        BigDecimal rounded = amount.setScale(2, RoundingMode.HALF_UP);
        boolean negative = rounded.signum() < 0;
        rounded = rounded.abs();

        long rupees = rounded.longValue();
        int paise = rounded.subtract(BigDecimal.valueOf(rupees)).movePointRight(2).intValue();

        StringBuilder words = new StringBuilder();
        if (negative) {
            words.append("Minus ");
        }
        words.append(rupees == 0 ? "Zero" : indianWords(rupees)).append(" Rupees");
        if (paise > 0) {
            words.append(" and ").append(twoDigits(paise)).append(" Paise");
        }
        return words.append(" Only").toString();
    }

    private static String indianWords(long value) {
        StringBuilder words = new StringBuilder();
        appendGroup(words, value / 10_000_000, "Crore");
        appendGroup(words, (value / 100_000) % 100, "Lakh");
        appendGroup(words, (value / 1_000) % 100, "Thousand");
        appendGroup(words, (value / 100) % 10, "Hundred");

        long remainder = value % 100;
        if (remainder > 0) {
            if (!words.isEmpty()) {
                words.append("and ");
            }
            words.append(twoDigits((int) remainder)).append(' ');
        }
        return words.toString().trim();
    }

    private static void appendGroup(StringBuilder words, long value, String label) {
        if (value > 0) {
            words.append(twoDigits((int) value)).append(' ').append(label).append(' ');
        }
    }

    private static String twoDigits(int value) {
        if (value < 20) {
            return UNITS[value];
        }
        String tens = TENS[value / 10];
        int unit = value % 10;
        return unit == 0 ? tens : tens + " " + UNITS[unit];
    }
}
