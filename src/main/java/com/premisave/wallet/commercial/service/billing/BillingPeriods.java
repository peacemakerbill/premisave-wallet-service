package com.premisave.wallet.commercial.service.billing;

import com.premisave.wallet.commercial.entity.billing.Plan;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Billing period arithmetic. Period k starts at anchor + k * intervalCount intervals, always computed
 * from the anchor and never from the previous period, so a subscription started on 31 January bills on
 * 31 January, 28 February, 31 March and does not drift to the 28th. All times are UTC.
 */
public final class BillingPeriods {

    private BillingPeriods() {}

    public static Instant startOf(Instant anchor, Plan.Interval interval, int intervalCount, long index) {
        ZonedDateTime z = anchor.atZone(ZoneOffset.UTC);
        long n = Math.multiplyExact((long) intervalCount, index);
        ZonedDateTime r = switch (interval) {
            case DAY -> z.plusDays(n);
            case WEEK -> z.plusWeeks(n);
            case MONTH -> z.plusMonths(n);
            case YEAR -> z.plusYears(n);
        };
        return r.toInstant();
    }

    public static Instant endOf(Instant anchor, Plan.Interval interval, int intervalCount, long index) {
        return startOf(anchor, interval, intervalCount, index + 1);
    }

    /** The index of the period that contains t, or -1 when t is before the anchor. */
    public static long indexAt(Instant anchor, Plan.Interval interval, int intervalCount, Instant t) {
        if (t.isBefore(anchor)) return -1;
        ChronoUnit unit = switch (interval) {
            case DAY -> ChronoUnit.DAYS;
            case WEEK -> ChronoUnit.WEEKS;
            case MONTH -> ChronoUnit.MONTHS;
            case YEAR -> ChronoUnit.YEARS;
        };
        ZonedDateTime a = anchor.atZone(ZoneOffset.UTC);
        ZonedDateTime tz = t.atZone(ZoneOffset.UTC);
        long k = Math.max(0, unit.between(a, tz) / intervalCount);
        while (k > 0 && startOf(anchor, interval, intervalCount, k).isAfter(t)) k--;
        while (!startOf(anchor, interval, intervalCount, k + 1).isAfter(t)) k++;
        return k;
    }

    /**
     * The billingPeriod of an invoice: the date of the period start at the precision of the interval.
     * A schedule restarted by a plan change or a resume adds its generation so labels stay unique.
     */
    public static String label(Instant start, Plan.Interval interval, int generation) {
        ZonedDateTime z = start.atZone(ZoneOffset.UTC);
        String base = switch (interval) {
            case DAY, WEEK -> String.format("%04d-%02d-%02d", z.getYear(), z.getMonthValue(), z.getDayOfMonth());
            case MONTH -> String.format("%04d-%02d", z.getYear(), z.getMonthValue());
            case YEAR -> String.format("%04d", z.getYear());
        };
        return generation == 0 ? base : base + "#" + generation;
    }
}
