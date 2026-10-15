package com.premisave.wallet.commercial.rail.stripe;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Set;

/** Stripe amounts are whole minor units. Zero decimal currencies have none; three decimal ones are not used. */
public final class StripeMoney {

    private static final Set<String> ZERO_DECIMAL = Set.of("BIF", "CLP", "DJF", "GNF", "JPY", "KMF", "KRW", "MGA",
            "PYG", "RWF", "UGX", "VND", "VUV", "XAF", "XOF", "XPF");
    private static final Set<String> THREE_DECIMAL = Set.of("BHD", "JOD", "KWD", "OMR", "TND");

    private StripeMoney() {}

    public static boolean supported(String currency) {
        return currency != null && !THREE_DECIMAL.contains(currency.toUpperCase(Locale.ROOT));
    }

    public static long minorUnits(BigDecimal amount, String currency) {
        String c = currency.toUpperCase(Locale.ROOT);
        if (!supported(c)) throw new IllegalArgumentException(c + " is not supported for card payments");
        BigDecimal scaled = ZERO_DECIMAL.contains(c) ? amount.setScale(0, RoundingMode.HALF_UP)
                : amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP);
        return scaled.longValueExact();
    }

    public static BigDecimal fromMinor(long minor, String currency) {
        return ZERO_DECIMAL.contains(currency.toUpperCase(Locale.ROOT)) ? BigDecimal.valueOf(minor)
                : BigDecimal.valueOf(minor, 2);
    }
}
