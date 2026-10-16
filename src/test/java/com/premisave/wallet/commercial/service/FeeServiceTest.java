package com.premisave.wallet.commercial.service;

import com.premisave.wallet.commercial.config.CommercialProperties.FeeRule;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FeeServiceTest {

    @Test
    void percentOnly() {
        assertEquals(50L, FeeService.compute(new FeeRule(200, 0L, null, null), 2500));
    }

    @Test
    void percentPlusFlat() {
        // 2.9% of 10.00 is 29 cents, plus 30.
        assertEquals(59L, FeeService.compute(new FeeRule(290, 30L, null, null), 1000));
    }

    @Test
    void percentRoundsHalfUp() {
        // 2% of 25 cents is 0.5 cent, which rounds up to 1.
        assertEquals(1L, FeeService.compute(new FeeRule(200, 0L, null, null), 25));
    }

    @Test
    void minimumApplies() {
        assertEquals(10L, FeeService.compute(new FeeRule(200, 0L, 10L, null), 100));
    }

    @Test
    void maximumApplies() {
        assertEquals(500L, FeeService.compute(new FeeRule(100, 0L, 50L, 500L), 10_000_000));
    }

    @Test
    void zeroAmountStillHonoursFlatAndMinimum() {
        assertEquals(20L, FeeService.compute(new FeeRule(100, 20L, null, null), 0));
    }
}
