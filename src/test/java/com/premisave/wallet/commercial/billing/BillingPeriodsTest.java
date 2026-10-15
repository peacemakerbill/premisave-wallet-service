package com.premisave.wallet.commercial.billing;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BillingPeriodsTest {

    private static Instant t(String s) { return Instant.parse(s); }

    @Test
    void monthEndsDoNotDrift() {
        Instant anchor = t("2026-01-31T10:00:00Z");
        assertEquals(t("2026-01-31T10:00:00Z"), BillingPeriods.startOf(anchor, Plan.Interval.MONTH, 1, 0));
        assertEquals(t("2026-02-28T10:00:00Z"), BillingPeriods.startOf(anchor, Plan.Interval.MONTH, 1, 1));
        assertEquals(t("2026-03-31T10:00:00Z"), BillingPeriods.startOf(anchor, Plan.Interval.MONTH, 1, 2));
        assertEquals(t("2026-04-30T10:00:00Z"), BillingPeriods.startOf(anchor, Plan.Interval.MONTH, 1, 3));
    }

    @Test
    void endIsTheNextStart() {
        Instant anchor = t("2026-10-14T00:00:00Z");
        assertEquals(t("2026-11-14T00:00:00Z"), BillingPeriods.endOf(anchor, Plan.Interval.MONTH, 1, 0));
        assertEquals(t("2026-10-21T00:00:00Z"), BillingPeriods.endOf(anchor, Plan.Interval.WEEK, 1, 0));
        assertEquals(t("2026-10-16T00:00:00Z"), BillingPeriods.endOf(anchor, Plan.Interval.DAY, 2, 0));
        assertEquals(t("2027-10-14T00:00:00Z"), BillingPeriods.endOf(anchor, Plan.Interval.YEAR, 1, 0));
        assertEquals(t("2027-01-14T00:00:00Z"), BillingPeriods.endOf(anchor, Plan.Interval.MONTH, 3, 0));
    }

    @Test
    void leapDayAnniversaryFallsBackToTheLastDayOfFebruary() {
        Instant anchor = t("2028-02-29T00:00:00Z");
        assertEquals(t("2029-02-28T00:00:00Z"), BillingPeriods.startOf(anchor, Plan.Interval.YEAR, 1, 1));
        assertEquals(t("2032-02-29T00:00:00Z"), BillingPeriods.startOf(anchor, Plan.Interval.YEAR, 1, 4));
    }

    @Test
    void indexAtFindsThePeriodContainingATime() {
        Instant anchor = t("2026-01-31T10:00:00Z");
        assertEquals(-1, BillingPeriods.indexAt(anchor, Plan.Interval.MONTH, 1, t("2026-01-31T09:59:59Z")));
        assertEquals(0, BillingPeriods.indexAt(anchor, Plan.Interval.MONTH, 1, t("2026-01-31T10:00:00Z")));
        assertEquals(0, BillingPeriods.indexAt(anchor, Plan.Interval.MONTH, 1, t("2026-02-28T09:59:59Z")));
        assertEquals(1, BillingPeriods.indexAt(anchor, Plan.Interval.MONTH, 1, t("2026-02-28T10:00:00Z")));
        assertEquals(2, BillingPeriods.indexAt(anchor, Plan.Interval.MONTH, 1, t("2026-03-31T10:00:00Z")));
        assertEquals(10, BillingPeriods.indexAt(anchor, Plan.Interval.DAY, 1, t("2026-02-10T12:00:00Z")));
        assertEquals(1, BillingPeriods.indexAt(anchor, Plan.Interval.MONTH, 3, t("2026-05-01T00:00:00Z")));
    }

    @Test
    void labelsFollowTheIntervalAndGeneration() {
        Instant start = t("2026-10-14T19:03:00Z");
        assertEquals("2026-10", BillingPeriods.label(start, Plan.Interval.MONTH, 0));
        assertEquals("2026-10-14", BillingPeriods.label(start, Plan.Interval.DAY, 0));
        assertEquals("2026-10-14", BillingPeriods.label(start, Plan.Interval.WEEK, 0));
        assertEquals("2026", BillingPeriods.label(start, Plan.Interval.YEAR, 0));
        assertEquals("2026-10#2", BillingPeriods.label(start, Plan.Interval.MONTH, 2));
    }
}
