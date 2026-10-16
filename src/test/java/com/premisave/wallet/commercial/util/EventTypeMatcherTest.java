package com.premisave.wallet.commercial.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventTypeMatcherTest {

    @Test
    void exactMatch() {
        assertTrue(EventTypeMatcher.matches("payment.succeeded", "payment.succeeded"));
        assertFalse(EventTypeMatcher.matches("payment.succeeded", "payment.failed"));
    }

    @Test
    void groupWildcard() {
        assertTrue(EventTypeMatcher.matches("payment.*", "payment.failed"));
        assertFalse(EventTypeMatcher.matches("payment.*", "payout.failed"));
        assertFalse(EventTypeMatcher.matches("pay.*", "payment.failed"));
    }

    @Test
    void everything() {
        assertTrue(EventTypeMatcher.matches("*", "refund.succeeded"));
    }

    @Test
    void anyOfSeveral() {
        assertTrue(EventTypeMatcher.matchesAny(List.of("payout.failed", "payment.*"), "payment.pending"));
        assertFalse(EventTypeMatcher.matchesAny(List.of("payout.failed"), "payment.pending"));
        assertFalse(EventTypeMatcher.matchesAny(null, "payment.pending"));
    }

    @Test
    void validPatterns() {
        assertTrue(EventTypeMatcher.isValidPattern("*"));
        assertTrue(EventTypeMatcher.isValidPattern("payment.*"));
        assertTrue(EventTypeMatcher.isValidPattern("payout.batch.completed"));
        assertFalse(EventTypeMatcher.isValidPattern("pay*"));
        assertFalse(EventTypeMatcher.isValidPattern("Payment.Succeeded"));
        assertFalse(EventTypeMatcher.isValidPattern(""));
        assertFalse(EventTypeMatcher.isValidPattern(null));
    }
}
