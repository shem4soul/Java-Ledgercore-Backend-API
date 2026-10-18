package com.ledgercore.common;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Set;

/** Helpers for integer minor-unit money. Amounts are never held in floating point. */
public final class Money {
    public static final Set<String> SUPPORTED_CURRENCIES = Set.of("NGN", "USD", "GBP", "EUR");

    private Money() {}

    public static boolean isSupported(String currency) {
        return currency != null && SUPPORTED_CURRENCIES.contains(currency);
    }

    /** 125050 NGN -> "NGN 1,250.50" */
    public static String format(long minor, String currency) {
        BigDecimal major = BigDecimal.valueOf(minor, 2);
        DecimalFormat df = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ROOT));
        return currency + " " + df.format(major);
    }
}
