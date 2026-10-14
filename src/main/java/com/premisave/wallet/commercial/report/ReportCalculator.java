package com.premisave.wallet.commercial.report;

import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.util.Money;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turns report buckets into the blocks of section 12 of the endpoint reference.
 *
 * Only money that really moved is counted as volume: successful transactions.
 * Failed, timed out and cancelled ones are counted separately so a merchant can
 * see how many attempts did not go through. A payout later reversed by the
 * provider stops counting as volume but its fee, which is kept, still counts.
 *
 * net is the change in available balance these transactions caused: collections
 * less their fees, minus payouts, refunds, settlements and transfers with
 * their fees.
 */
public final class ReportCalculator {

    public enum GroupBy { DAY, WEEK, MONTH, METHOD, APP }

    private ReportCalculator() {}

    private static final class Tally {
        long count;
        long volume;
        long fees;
        long failed;
        long reversed;
    }

    public static Map<String, Object> block(Collection<ReportBucket> buckets) {
        Tally collections = new Tally();
        Tally payouts = new Tally();
        Tally refunds = new Tally();
        Tally settlements = new Tally();
        Tally transfers = new Tally();
        Map<String, Long> byMethod = new TreeMap<>();

        for (ReportBucket b : buckets) {
            switch (b.type()) {
                case PAYMENT, SUBSCRIPTION_CHARGE -> {
                    if (b.status() == TxStatus.SUCCESS) {
                        success(collections, b);
                        byMethod.merge(b.method() == null ? "UNKNOWN" : b.method().name(), b.amountCents(), Long::sum);
                    } else if (didNotComplete(b.status())) {
                        collections.failed += b.count();
                    }
                }
                case PAYOUT -> debit(payouts, b);
                case REFUND -> debit(refunds, b);
                case SETTLEMENT -> debit(settlements, b);
                case TRANSFER -> debit(transfers, b);
                default -> { }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("count", collections.count);
        c.put("volume", Money.format(collections.volume));
        c.put("fees", Money.format(collections.fees));
        c.put("failed", collections.failed);
        Map<String, Object> methods = new LinkedHashMap<>();
        byMethod.forEach((k, v) -> methods.put(k, Money.format(v)));
        c.put("byMethod", methods);
        out.put("collections", c);

        Map<String, Object> p = outflow(payouts);
        p.put("failed", payouts.failed);
        p.put("reversed", payouts.reversed);
        out.put("payouts", p);
        out.put("refunds", outflow(refunds));
        out.put("settlements", outflow(settlements));
        out.put("transfers", outflow(transfers));

        long net = (collections.volume - collections.fees)
                - (payouts.volume + payouts.fees)
                - (refunds.volume + refunds.fees)
                - (settlements.volume + settlements.fees)
                - (transfers.volume + transfers.fees);
        out.put("net", Money.format(net));
        return out;
    }

    /** One block per group, in key order. DAY, WEEK (starting Monday), MONTH, METHOD or APP. */
    public static List<Map<String, Object>> grouped(Collection<ReportBucket> buckets, GroupBy by) {
        Map<String, List<ReportBucket>> groups = new TreeMap<>();
        for (ReportBucket b : buckets) {
            groups.computeIfAbsent(keyOf(b, by), k -> new java.util.ArrayList<>()).add(b);
        }
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        groups.forEach((key, list) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("group", key);
            row.putAll(block(list));
            rows.add(row);
        });
        return rows;
    }

    static String keyOf(ReportBucket b, GroupBy by) {
        return switch (by) {
            case DAY -> b.day();
            case WEEK -> LocalDate.parse(b.day()).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString();
            case MONTH -> b.day().substring(0, 7);
            case METHOD -> b.method() == null ? "NONE" : b.method().name();
            case APP -> b.appId() == null ? "NONE" : b.appId();
        };
    }

    private static boolean didNotComplete(TxStatus s) {
        return s == TxStatus.FAILED || s == TxStatus.TIMED_OUT || s == TxStatus.CANCELLED;
    }

    private static void success(Tally t, ReportBucket b) {
        t.count += b.count();
        t.volume += b.amountCents();
        t.fees += b.feeCents();
    }

    private static void debit(Tally t, ReportBucket b) {
        if (b.status() == TxStatus.SUCCESS) {
            success(t, b);
        } else if (b.status() == TxStatus.REVERSED) {
            t.reversed += b.count();
            t.fees += b.feeCents();
        } else if (didNotComplete(b.status())) {
            t.failed += b.count();
        }
    }

    private static Map<String, Object> outflow(Tally t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", t.count);
        m.put("volume", Money.format(t.volume));
        m.put("fees", Money.format(t.fees));
        return m;
    }
}
