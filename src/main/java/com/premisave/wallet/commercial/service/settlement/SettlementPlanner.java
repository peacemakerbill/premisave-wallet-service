package com.premisave.wallet.commercial.service.settlement;

import com.premisave.wallet.commercial.entity.settlement.SettlementSchedule;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.function.LongUnaryOperator;

/**
 * The arithmetic behind automatic settlements, kept free of any database or
 * clock so every rule can be tested on its own: when a run is due, and how
 * large it can be once the fee and the reserve the merchant keeps are taken
 * into account.
 */
public final class SettlementPlanner {

    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private SettlementPlanner() {}

    /** due is false when nothing should happen now; runKey names the period this run would cover. */
    public record Decision(boolean due, String runKey) {
        static Decision no() { return new Decision(false, null); }
    }

    public static Decision decide(SettlementSchedule s, Instant now, long availableCents) {
        var at = now.atOffset(ZoneOffset.UTC);
        LocalDate today = at.toLocalDate();
        switch (s.getMode()) {
            case DAILY -> {
                if (!reached(s, at.toLocalTime())) return Decision.no();
                return fresh(s, "D" + today);
            }
            case WEEKLY -> {
                if (s.getDayOfWeek() == null || at.getDayOfWeek() != s.getDayOfWeek()) return Decision.no();
                if (!reached(s, at.toLocalTime())) return Decision.no();
                return fresh(s, "W" + today);
            }
            case THRESHOLD -> {
                long spendable = availableCents - s.getKeepReserveCents();
                if (s.getThresholdCents() <= 0 || spendable < s.getThresholdCents()) return Decision.no();
                return fresh(s, "T" + at.format(MINUTE));
            }
            default -> {
                return Decision.no();
            }
        }
    }

    private static boolean reached(SettlementSchedule s, LocalTime now) {
        LocalTime runAt = s.getRunAtUtc() == null ? LocalTime.of(6, 0) : LocalTime.parse(s.getRunAtUtc());
        return !now.isBefore(runAt);
    }

    private static Decision fresh(SettlementSchedule s, String key) {
        return key.equals(s.getLastRunKey()) ? Decision.no() : new Decision(true, key);
    }

    /**
     * The amount to settle, in USD cents, or 0 when there is nothing worth settling. The balance
     * left after the reserve must cover the amount and its fee, and the amount must reach the
     * minimum the merchant set.
     */
    public static long amountFor(SettlementSchedule s, long availableCents, LongUnaryOperator fee) {
        long spendable = availableCents - s.getKeepReserveCents();
        if (spendable <= 0) return 0;
        long amount = largestAmount(spendable, fee);
        if (amount <= 0 || amount < s.getMinimumCents()) return 0;
        return amount;
    }

    /** The largest amount a for which a plus fee(a) does not exceed the money available. */
    public static long largestAmount(long spendable, LongUnaryOperator fee) {
        long a = spendable - fee.applyAsLong(spendable);
        while (a > 0 && a + fee.applyAsLong(a) > spendable) a--;
        while (a >= 0 && (a + 1) + fee.applyAsLong(a + 1) <= spendable) a++;
        return Math.max(a, 0);
    }
}
