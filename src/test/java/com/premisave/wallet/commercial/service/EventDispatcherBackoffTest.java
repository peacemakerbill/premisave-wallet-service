package com.premisave.wallet.commercial.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EventDispatcherBackoffTest {

    @Test
    void firstRetryWaitsAboutThirtySeconds() {
        for (int i = 0; i < 50; i++) {
            long s = EventDispatcher.backoffSeconds(1);
            assertTrue(s >= 30 && s <= 36, "got " + s);
        }
    }

    @Test
    void doublesEachAttempt() {
        long s = EventDispatcher.backoffSeconds(3);
        assertTrue(s >= 120 && s <= 144, "got " + s);
    }

    @Test
    void neverExceedsSixHoursPlusJitter() {
        for (int attempts : new int[] {12, 20, 40, 1000}) {
            long s = EventDispatcher.backoffSeconds(attempts);
            assertTrue(s >= 6 * 3600 && s <= (long) (6 * 3600 * 1.2), "attempts " + attempts + " got " + s);
        }
    }
}
