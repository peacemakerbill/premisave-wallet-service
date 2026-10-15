package com.premisave.wallet.commercial.rail.stripe;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StripeMoneyTest {

    @Test
    void twoDecimalCurrenciesUseCents() {
        assertEquals(1050L, StripeMoney.minorUnits(new BigDecimal("10.50"), "USD"));
        assertEquals(1L, StripeMoney.minorUnits(new BigDecimal("0.005"), "usd"));
        assertEquals(new BigDecimal("10.50"), StripeMoney.fromMinor(1050, "USD"));
    }

    @Test
    void zeroDecimalCurrenciesUseWholeUnits() {
        assertEquals(500L, StripeMoney.minorUnits(new BigDecimal("500"), "UGX"));
        assertEquals(new BigDecimal("500"), StripeMoney.fromMinor(500, "RWF"));
    }

    @Test
    void threeDecimalCurrenciesAreRefused() {
        assertFalse(StripeMoney.supported("KWD"));
        assertTrue(StripeMoney.supported("USD"));
        assertThrows(IllegalArgumentException.class, () -> StripeMoney.minorUnits(BigDecimal.ONE, "KWD"));
    }
}
