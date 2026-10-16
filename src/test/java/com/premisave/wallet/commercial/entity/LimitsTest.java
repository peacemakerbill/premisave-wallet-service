package com.premisave.wallet.commercial.entity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LimitsTest {

    private static Limits limits(String single, String daily, Integer payouts) {
        Limits l = new Limits();
        if (single != null) l.setSingleTransactionUsd(new BigDecimal(single));
        if (daily != null) l.setDailyVolumeUsd(new BigDecimal(daily));
        l.setDailyPayoutCount(payouts);
        return l;
    }

    @Test
    void emptyMeansNothingSet() {
        assertTrue(new Limits().isEmpty());
        assertFalse(limits("1", null, null).isEmpty());
    }

    @Test
    void childFitsWhenEveryValueIsWithinParent() {
        assertTrue(limits("500", "5000", 10).fitsWithin(limits("1000", "10000", 20)));
        assertTrue(limits("1000", null, null).fitsWithin(limits("1000", "10000", 20)));
    }

    @Test
    void childDoesNotFitWhenAnyValueExceedsParent() {
        assertFalse(limits("1500", null, null).fitsWithin(limits("1000", null, null)));
        assertFalse(limits(null, "20000", null).fitsWithin(limits(null, "10000", null)));
        assertFalse(limits(null, null, 30).fitsWithin(limits(null, null, 20)));
    }

    @Test
    void unsetParentImposesNothing() {
        assertTrue(limits("999999", "999999", 999).fitsWithin(new Limits()));
        assertTrue(limits("1", null, null).fitsWithin(null));
    }

    @Test
    void stricterTakesTheLowerValueEachTime() {
        Limits s = Limits.stricter(limits("1000", "5000", 10), limits("500", "9000", 20));
        assertEquals(new BigDecimal("500"), s.getSingleTransactionUsd());
        assertEquals(new BigDecimal("5000"), s.getDailyVolumeUsd());
        assertEquals(10, s.getDailyPayoutCount());
    }

    @Test
    void stricterHandlesNullsOnEitherSide() {
        Limits s = Limits.stricter(null, limits("500", null, 7));
        assertEquals(new BigDecimal("500"), s.getSingleTransactionUsd());
        assertEquals(7, s.getDailyPayoutCount());
        assertNull(s.getDailyVolumeUsd());
        assertTrue(Limits.stricter(null, null).isEmpty());
    }
}
