package com.premisave.wallet.commercial.billing;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryPolicyTest {

    private static final Instant DUE = Instant.parse("2026-10-14T00:00:00Z");
    private static final List<Integer> SCHEDULE = List.of(1, 3, 5);

    @Test
    void retriesFollowTheScheduleFromTheDueDate() {
        Instant now = DUE.plusSeconds(60);
        assertEquals(Instant.parse("2026-10-15T00:00:00Z"), RetryPolicy.nextRetryAt(DUE, SCHEDULE, 1, now));
        assertEquals(Instant.parse("2026-10-17T00:00:00Z"), RetryPolicy.nextRetryAt(DUE, SCHEDULE, 2, now));
        assertEquals(Instant.parse("2026-10-19T00:00:00Z"), RetryPolicy.nextRetryAt(DUE, SCHEDULE, 3, now));
    }

    @Test
    void scheduleRunsOut() {
        assertNull(RetryPolicy.nextRetryAt(DUE, SCHEDULE, 4, DUE));
        assertNull(RetryPolicy.nextRetryAt(DUE, List.of(), 1, DUE));
        assertNull(RetryPolicy.nextRetryAt(DUE, SCHEDULE, 0, DUE));
    }

    @Test
    void aLateSchedulerDoesNotFireRetriesBackToBack() {
        Instant late = DUE.plusSeconds(10 * 86400);
        assertEquals(late.plus(RetryPolicy.MIN_GAP), RetryPolicy.nextRetryAt(DUE, SCHEDULE, 1, late));
    }

    @Test
    void onlyProviderErrorsAreTransient() {
        assertTrue(RetryPolicy.isTransientCode("PROVIDER_ERROR"));
        assertFalse(RetryPolicy.isTransientCode("DECLINED"));
        assertFalse(RetryPolicy.isTransientCode(null));
    }
}
