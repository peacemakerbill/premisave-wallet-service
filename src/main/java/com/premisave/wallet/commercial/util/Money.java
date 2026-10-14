package com.premisave.wallet.commercial.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * USD ledger arithmetic. Balances are held as whole cents in a long so they
 * can be changed with an atomic $inc and compared in a query guard, which
 * is not possible with BigDecimal (stored as text by default).
 */
public final class Money {

    public static final String BASE_CURRENCY = "USD";

    private Money() {}

    /** Rounds to cents (half up) and returns the amount as whole cents. */
    public static long toCents(BigDecimal usd) {
        return usd.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    }

    public static BigDecimal fromCents(long cents) {
        return BigDecimal.valueOf(cents, 2);
    }

    public static String format(long cents) {
        return fromCents(cents).toPlainString();
    }

    /** True when the amount has more than two decimal places. */
    public static boolean hasMoreThanCents(BigDecimal amount) {
        return amount.stripTrailingZeros().scale() > 2;
    }

    /** native amount / (native units per 1 USD), rounded to cents. */
    public static long nativeToUsdCents(BigDecimal nativeAmount, BigDecimal nativePerUsd) {
        return toCents(nativeAmount.divide(nativePerUsd, 2, RoundingMode.HALF_UP));
    }
}
