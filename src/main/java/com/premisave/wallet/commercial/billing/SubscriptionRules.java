package com.premisave.wallet.commercial.billing;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * The rules of a subscription as plain functions: which changes are allowed in which state, where the
 * schedule starts, and what the next billing cycle is. No database and no clock inside.
 */
public final class SubscriptionRules {

    private SubscriptionRules() {}

    /** Where a subscription's schedule begins. */
    public record Schedule(Subscription.State state, Instant trialEnd, Instant anchor, Instant periodStart,
                           Instant periodEnd, Instant nextBillingAt) {}

    /** One billing cycle: the period, what it costs, and the schedule values to store once it is invoiced. */
    public record Cycle(long index, Instant start, Instant end, String label, Instant anchor, int generation,
                        Subscription.Terms terms, String planId, boolean planSwitched, BigDecimal amount,
                        boolean includesSetupFee) {}

    /** Where a resumed subscription's schedule stands. */
    public record Resumed(Subscription.State state, Instant anchor, int generation, long periodIndex,
                          Instant periodStart, Instant periodEnd, Instant nextBillingAt) {}

    public static boolean canPause(Subscription.State s) {
        return s == Subscription.State.ACTIVE || s == Subscription.State.TRIALING || s == Subscription.State.PAST_DUE;
    }

    public static boolean canResume(Subscription.State s) {
        return s == Subscription.State.PAUSED;
    }

    public static boolean canCancel(Subscription.State s) {
        return !s.isTerminal();
    }

    public static boolean canUpdate(Subscription.State s) {
        return !s.isTerminal();
    }

    /** Only a subscription that is billing normally can run to the end of its period; the rest end at once. */
    public static boolean canCancelAtPeriodEnd(Subscription.State s) {
        return s == Subscription.State.ACTIVE || s == Subscription.State.TRIALING;
    }

    public static Schedule initialSchedule(Instant start, int trialDays, Plan.Interval interval, int intervalCount) {
        if (trialDays > 0) {
            Instant trialEnd = start.plus(Duration.ofDays(trialDays));
            return new Schedule(Subscription.State.TRIALING, trialEnd, trialEnd, start, trialEnd, trialEnd);
        }
        Instant end = BillingPeriods.endOf(start, interval, intervalCount, 0);
        return new Schedule(Subscription.State.ACTIVE, null, start, start, end, start);
    }

    /** The cycle that starts at the subscription's nextBillingAt. */
    public static Cycle cycleFor(Subscription s) {
        Subscription.Terms terms = s.getTerms();
        String planId = s.getPlanId();
        Instant anchor = s.getAnchor();
        int generation = s.getGeneration();
        long index = s.getPeriodIndex() + 1;
        boolean switched = false;
        if (s.getPendingTerms() != null) {
            terms = s.getPendingTerms();
            planId = s.getPendingPlanId() != null ? s.getPendingPlanId() : planId;
            anchor = s.getNextBillingAt();
            generation = generation + 1;
            index = 0;
            switched = true;
        }
        Instant start = BillingPeriods.startOf(anchor, terms.getInterval(), terms.getIntervalCount(), index);
        Instant end = BillingPeriods.endOf(anchor, terms.getInterval(), terms.getIntervalCount(), index);
        boolean setup = !s.isSetupFeeBilled() && terms.getSetupFee() != null && terms.getSetupFee().signum() > 0;
        BigDecimal amount = terms.getAmount().multiply(BigDecimal.valueOf(s.getQuantity()));
        if (setup) amount = amount.add(terms.getSetupFee());
        return new Cycle(index, start, end, BillingPeriods.label(start, terms.getInterval(), generation),
                anchor, generation, terms, planId, switched, amount, setup);
    }

    /**
     * Resuming without charging: missed boundaries are skipped, the customer is not billed for the time
     * the subscription was paused, and billing continues from the next boundary.
     */
    public static Resumed resumeWithoutBilling(Subscription s, Instant now) {
        Subscription.State state = s.getStatePrePause() == null ? Subscription.State.ACTIVE : s.getStatePrePause();
        Instant next = s.getNextBillingAt();
        long idx = s.getPeriodIndex();
        Instant ps = s.getPeriodStart();
        Instant pe = s.getPeriodEnd();
        if (state == Subscription.State.TRIALING && s.getTrialEnd() != null && !s.getTrialEnd().isAfter(now)) {
            state = Subscription.State.ACTIVE;
        }
        if (next != null && !next.isAfter(now) && s.getAnchor() != null && s.getPendingTerms() == null) {
            Subscription.Terms t = s.getTerms();
            long at = BillingPeriods.indexAt(s.getAnchor(), t.getInterval(), t.getIntervalCount(), now);
            if (at >= 0 && at >= idx) {
                idx = at;
                ps = BillingPeriods.startOf(s.getAnchor(), t.getInterval(), t.getIntervalCount(), at);
                pe = BillingPeriods.endOf(s.getAnchor(), t.getInterval(), t.getIntervalCount(), at);
                next = pe;
            }
        } else if (next != null && !next.isAfter(now) && s.getPendingTerms() != null) {
            // A plan switch was waiting for this boundary: start the new plan's schedule from now.
            Subscription.Terms t = s.getPendingTerms();
            return new Resumed(state, now, s.getGeneration() + 1, 0,
                    now, BillingPeriods.endOf(now, t.getInterval(), t.getIntervalCount(), 0),
                    BillingPeriods.endOf(now, t.getInterval(), t.getIntervalCount(), 0));
        }
        return new Resumed(state, s.getAnchor(), s.getGeneration(), idx, ps, pe, next);
    }

    /** The next boundary after a subscription was resumed with billNow: a fresh schedule due immediately. */
    public static Resumed resumeAndBill(Subscription s, Instant now) {
        return new Resumed(Subscription.State.ACTIVE, now, s.getGeneration() + 1, -1, now,
                BillingPeriods.endOf(now, termsInForce(s).getInterval(), termsInForce(s).getIntervalCount(), 0), now);
    }

    /** The terms the next cycle will use: a pending plan switch counts. */
    public static Subscription.Terms termsInForce(Subscription s) {
        return s.getPendingTerms() != null ? s.getPendingTerms() : s.getTerms();
    }
}
