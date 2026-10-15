package com.premisave.wallet.commercial.settlement;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementPlannerTest {

    private static SettlementSchedule daily(String runAt, String lastKey) {
        SettlementSchedule s = new SettlementSchedule();
        s.setMode(SettlementSchedule.Mode.DAILY);
        s.setRunAtUtc(runAt);
        s.setLastRunKey(lastKey);
        return s;
    }

    @Test
    void dailyWaitsForTheRunTime() {
        SettlementSchedule s = daily("06:00", null);
        assertFalse(SettlementPlanner.decide(s, Instant.parse("2026-10-14T05:59:00Z"), 100_000).due());
        SettlementPlanner.Decision d = SettlementPlanner.decide(s, Instant.parse("2026-10-14T06:00:00Z"), 100_000);
        assertTrue(d.due());
        assertEquals("D2026-10-14", d.runKey());
    }

    @Test
    void dailyRunsOncePerDay() {
        SettlementSchedule s = daily("06:00", "D2026-10-14");
        assertFalse(SettlementPlanner.decide(s, Instant.parse("2026-10-14T18:00:00Z"), 100_000).due());
        assertTrue(SettlementPlanner.decide(s, Instant.parse("2026-10-15T06:01:00Z"), 100_000).due());
    }

    @Test
    void weeklyRunsOnlyOnItsDay() {
        SettlementSchedule s = new SettlementSchedule();
        s.setMode(SettlementSchedule.Mode.WEEKLY);
        s.setDayOfWeek(DayOfWeek.MONDAY);
        s.setRunAtUtc("06:00");
        assertFalse(SettlementPlanner.decide(s, Instant.parse("2026-10-14T07:00:00Z"), 100_000).due()); // a Wednesday
        assertTrue(SettlementPlanner.decide(s, Instant.parse("2026-10-12T07:00:00Z"), 100_000).due());   // a Monday
    }

    @Test
    void thresholdCountsBalanceAfterTheReserve() {
        SettlementSchedule s = new SettlementSchedule();
        s.setMode(SettlementSchedule.Mode.THRESHOLD);
        s.setThresholdCents(50_000);
        s.setKeepReserveCents(10_000);
        Instant now = Instant.parse("2026-10-14T12:30:00Z");
        assertFalse(SettlementPlanner.decide(s, now, 59_999).due());
        SettlementPlanner.Decision d = SettlementPlanner.decide(s, now, 60_000);
        assertTrue(d.due());
        assertEquals("T202610141230", d.runKey());
    }

    @Test
    void manualNeverRuns() {
        SettlementSchedule s = new SettlementSchedule();
        assertFalse(SettlementPlanner.decide(s, Instant.parse("2026-10-14T12:30:00Z"), 1_000_000).due());
    }

    @Test
    void amountLeavesRoomForFeeAndReserve() {
        SettlementSchedule s = new SettlementSchedule();
        s.setKeepReserveCents(10_000);
        // 1 percent fee: amount plus fee must fit in 90,000
        long amount = SettlementPlanner.amountFor(s, 100_000, c -> c / 100);
        assertTrue(amount + amount / 100 <= 90_000);
        assertTrue((amount + 1) + (amount + 1) / 100 > 90_000);
    }

    @Test
    void amountBelowTheMinimumIsSkipped() {
        SettlementSchedule s = new SettlementSchedule();
        s.setMinimumCents(5_000);
        assertEquals(0, SettlementPlanner.amountFor(s, 4_000, c -> 0));
        assertEquals(5_000, SettlementPlanner.amountFor(s, 5_000, c -> 0));
    }

    @Test
    void reserveLargerThanBalanceGivesNothing() {
        SettlementSchedule s = new SettlementSchedule();
        s.setKeepReserveCents(10_000);
        assertEquals(0, SettlementPlanner.amountFor(s, 10_000, c -> 0));
        assertEquals(0, SettlementPlanner.amountFor(s, 5_000, c -> 0));
    }

    @Test
    void feeLargerThanBalanceGivesNothing() {
        assertEquals(0, SettlementPlanner.largestAmount(20, c -> 50));
    }

    @Test
    void flatPlusPercentFeeFindsTheExactLargestAmount() {
        // fee = 20 cents + 1 percent
        long a = SettlementPlanner.largestAmount(10_000, c -> 20 + c / 100);
        assertTrue(a + 20 + a / 100 <= 10_000);
        assertTrue((a + 1) + 20 + (a + 1) / 100 > 10_000);
    }
}
