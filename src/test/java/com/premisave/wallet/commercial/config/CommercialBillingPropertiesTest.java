package com.premisave.wallet.commercial.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommercialBillingPropertiesTest {

    @Test
    void defaults() {
        CommercialBillingProperties p = new CommercialBillingProperties(null, null, null, null);
        assertEquals(3, p.upcomingDays());
        assertEquals(10, p.stuckMinutes());
        assertEquals(100, p.batchSize());
        assertEquals(3000, p.clockWaitMs());
    }

    @Test
    void validValuesAreKept() {
        CommercialBillingProperties p = new CommercialBillingProperties(7, 15, 50, 500);
        assertEquals(7, p.upcomingDays());
        assertEquals(15, p.stuckMinutes());
        assertEquals(50, p.batchSize());
        assertEquals(500, p.clockWaitMs());
    }

    @Test
    void outOfRangeValuesFallBackToTheDefaults() {
        CommercialBillingProperties p = new CommercialBillingProperties(0, 2, 5000, -1);
        assertEquals(3, p.upcomingDays());
        assertEquals(10, p.stuckMinutes());
        assertEquals(100, p.batchSize());
        assertEquals(3000, p.clockWaitMs());
    }
}
