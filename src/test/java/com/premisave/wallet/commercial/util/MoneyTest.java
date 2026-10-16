package com.premisave.wallet.commercial.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoneyTest {

    @Test
    void roundsHalfUpToCents() {
        assertEquals(1001L, Money.toCents(new BigDecimal("10.005")));
        assertEquals(1000L, Money.toCents(new BigDecimal("10.004")));
        assertEquals(2500L, Money.toCents(new BigDecimal("25")));
    }

    @Test
    void formatsWithTwoDecimals() {
        assertEquals("0.05", Money.format(5));
        assertEquals("25.00", Money.format(2500));
        assertEquals("1240.60", Money.format(124060));
    }

    @Test
    void detectsMoreThanTwoDecimals() {
        assertTrue(Money.hasMoreThanCents(new BigDecimal("1.234")));
        assertFalse(Money.hasMoreThanCents(new BigDecimal("1.20")));
        assertFalse(Money.hasMoreThanCents(new BigDecimal("1.2000")));
    }

    @Test
    void convertsNativeToUsdCents() {
        // 3225 KES at 129 KES per USD is 25 USD.
        assertEquals(2500L, Money.nativeToUsdCents(new BigDecimal("3225.00"), new BigDecimal("129")));
        assertEquals(100L, Money.nativeToUsdCents(new BigDecimal("129"), new BigDecimal("129")));
    }
}
