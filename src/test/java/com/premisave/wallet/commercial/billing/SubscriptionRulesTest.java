package com.premisave.wallet.commercial.billing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubscriptionRulesTest {

    private static Instant t(String s) { return Instant.parse(s); }

    private static Plan plan(String amount, String setup) {
        Plan p = new Plan();
        p.setName("Pro monthly");
        p.setAmount(new BigDecimal(amount));
        p.setCurrency("USD");
        p.setInterval(Plan.Interval.MONTH);
        p.setIntervalCount(1);
        p.setSetupFee(new BigDecimal(setup));
        p.setRetryScheduleDays(List.of(1, 3, 5));
        return p;
    }

    private static Subscription subscription(Instant start, int trialDays, String amount, String setup) {
        Subscription s = new Subscription();
        s.setTerms(Subscription.Terms.of(plan(amount, setup)));
        s.setPlanId("plan_pro");
        SubscriptionRules.Schedule sc = SubscriptionRules.initialSchedule(start, trialDays, Plan.Interval.MONTH, 1);
        s.setState(sc.state());
        s.setTrialEnd(sc.trialEnd());
        s.setAnchor(sc.anchor());
        s.setPeriodStart(sc.periodStart());
        s.setPeriodEnd(sc.periodEnd());
        s.setNextBillingAt(sc.nextBillingAt());
        return s;
    }

    @Test
    void withoutATrialTheFirstChargeIsDueAtOnce() {
        SubscriptionRules.Schedule sc = SubscriptionRules.initialSchedule(t("2026-10-14T00:00:00Z"), 0, Plan.Interval.MONTH, 1);
        assertEquals(Subscription.State.ACTIVE, sc.state());
        assertEquals(t("2026-10-14T00:00:00Z"), sc.nextBillingAt());
        assertEquals(t("2026-11-14T00:00:00Z"), sc.periodEnd());
        assertNull(sc.trialEnd());
    }

    @Test
    void aTrialPushesTheFirstChargeToTheTrialEnd() {
        SubscriptionRules.Schedule sc = SubscriptionRules.initialSchedule(t("2026-10-14T00:00:00Z"), 7, Plan.Interval.MONTH, 1);
        assertEquals(Subscription.State.TRIALING, sc.state());
        assertEquals(t("2026-10-21T00:00:00Z"), sc.trialEnd());
        assertEquals(t("2026-10-21T00:00:00Z"), sc.anchor());
        assertEquals(t("2026-10-21T00:00:00Z"), sc.nextBillingAt());
    }

    @Test
    void theFirstCycleStartsAtTheAnchor() {
        Subscription s = subscription(t("2026-10-14T00:00:00Z"), 0, "10.00", "0.00");
        SubscriptionRules.Cycle c = SubscriptionRules.cycleFor(s);
        assertEquals(0, c.index());
        assertEquals(t("2026-10-14T00:00:00Z"), c.start());
        assertEquals(t("2026-11-14T00:00:00Z"), c.end());
        assertEquals("2026-10", c.label());
        assertEquals("10.00", c.amount().toPlainString());
        assertFalse(c.includesSetupFee());
    }

    @Test
    void theSetupFeeIsAddedToTheFirstInvoiceOnly() {
        Subscription s = subscription(t("2026-10-14T00:00:00Z"), 0, "10.00", "5.00");
        assertEquals("15.00", SubscriptionRules.cycleFor(s).amount().toPlainString());
        assertTrue(SubscriptionRules.cycleFor(s).includesSetupFee());
        s.setSetupFeeBilled(true);
        assertEquals("10.00", SubscriptionRules.cycleFor(s).amount().toPlainString());
    }

    @Test
    void quantityMultipliesThePrice() {
        Subscription s = subscription(t("2026-10-14T00:00:00Z"), 0, "10.00", "0.00");
        s.setQuantity(3);
        assertEquals("30.00", SubscriptionRules.cycleFor(s).amount().toPlainString());
    }

    @Test
    void laterCyclesFollowTheAnchor() {
        Subscription s = subscription(t("2026-01-31T00:00:00Z"), 0, "10.00", "0.00");
        s.setPeriodIndex(1);
        SubscriptionRules.Cycle c = SubscriptionRules.cycleFor(s);
        assertEquals(2, c.index());
        assertEquals(t("2026-03-31T00:00:00Z"), c.start());
        assertEquals(t("2026-04-30T00:00:00Z"), c.end());
        assertEquals("2026-03", c.label());
    }

    @Test
    void aPendingPlanSwitchRestartsTheScheduleAtTheBoundary() {
        Subscription s = subscription(t("2026-10-14T00:00:00Z"), 0, "10.00", "0.00");
        s.setPeriodIndex(0);
        s.setNextBillingAt(t("2026-11-14T00:00:00Z"));
        Plan bigger = plan("25.00", "0.00");
        bigger.setInterval(Plan.Interval.YEAR);
        s.setPendingPlanId("plan_big");
        s.setPendingTerms(Subscription.Terms.of(bigger));
        SubscriptionRules.Cycle c = SubscriptionRules.cycleFor(s);
        assertTrue(c.planSwitched());
        assertEquals(0, c.index());
        assertEquals(1, c.generation());
        assertEquals("plan_big", c.planId());
        assertEquals(t("2026-11-14T00:00:00Z"), c.start());
        assertEquals(t("2027-11-14T00:00:00Z"), c.end());
        assertEquals("2026#1", c.label());
        assertEquals("25.00", c.amount().toPlainString());
    }

    @Test
    void whichChangesAreAllowedInWhichState() {
        assertTrue(SubscriptionRules.canPause(Subscription.State.ACTIVE));
        assertTrue(SubscriptionRules.canPause(Subscription.State.TRIALING));
        assertTrue(SubscriptionRules.canPause(Subscription.State.PAST_DUE));
        assertFalse(SubscriptionRules.canPause(Subscription.State.PAUSED));
        assertFalse(SubscriptionRules.canPause(Subscription.State.PENDING_AUTHORISATION));
        assertFalse(SubscriptionRules.canPause(Subscription.State.CANCELLED));
        assertTrue(SubscriptionRules.canResume(Subscription.State.PAUSED));
        assertFalse(SubscriptionRules.canResume(Subscription.State.ACTIVE));
        assertTrue(SubscriptionRules.canCancel(Subscription.State.PAST_DUE));
        assertFalse(SubscriptionRules.canCancel(Subscription.State.EXPIRED));
        assertTrue(SubscriptionRules.canCancelAtPeriodEnd(Subscription.State.ACTIVE));
        assertFalse(SubscriptionRules.canCancelAtPeriodEnd(Subscription.State.PAUSED));
        assertFalse(SubscriptionRules.canCancelAtPeriodEnd(Subscription.State.PAST_DUE));
        assertFalse(SubscriptionRules.canUpdate(Subscription.State.CANCELLED));
    }

    @Test
    void resumingSkipsTheBoundariesMissedWhilePaused() {
        Subscription s = subscription(t("2026-01-15T00:00:00Z"), 0, "10.00", "0.00");
        s.setPeriodIndex(0);
        s.setPeriodStart(t("2026-01-15T00:00:00Z"));
        s.setPeriodEnd(t("2026-02-15T00:00:00Z"));
        s.setNextBillingAt(t("2026-02-15T00:00:00Z"));
        s.setStatePrePause(Subscription.State.ACTIVE);
        SubscriptionRules.Resumed r = SubscriptionRules.resumeWithoutBilling(s, t("2026-05-20T00:00:00Z"));
        assertEquals(Subscription.State.ACTIVE, r.state());
        assertEquals(4, r.periodIndex());
        assertEquals(t("2026-05-15T00:00:00Z"), r.periodStart());
        assertEquals(t("2026-06-15T00:00:00Z"), r.nextBillingAt());
    }

    @Test
    void resumingBeforeTheNextBoundaryChangesNoDates() {
        Subscription s = subscription(t("2026-10-14T00:00:00Z"), 0, "10.00", "0.00");
        s.setPeriodIndex(0);
        s.setNextBillingAt(t("2026-11-14T00:00:00Z"));
        s.setStatePrePause(Subscription.State.ACTIVE);
        SubscriptionRules.Resumed r = SubscriptionRules.resumeWithoutBilling(s, t("2026-10-20T00:00:00Z"));
        assertEquals(0, r.periodIndex());
        assertEquals(t("2026-11-14T00:00:00Z"), r.nextBillingAt());
    }

    @Test
    void aTrialThatEndedWhilePausedBecomesActive() {
        Subscription s = subscription(t("2026-10-14T00:00:00Z"), 7, "10.00", "0.00");
        s.setStatePrePause(Subscription.State.TRIALING);
        SubscriptionRules.Resumed r = SubscriptionRules.resumeWithoutBilling(s, t("2026-11-02T00:00:00Z"));
        assertEquals(Subscription.State.ACTIVE, r.state());
        assertEquals(0, r.periodIndex() < 0 ? 0 : r.periodIndex());
        assertTrue(r.nextBillingAt().isAfter(t("2026-11-02T00:00:00Z")));
    }

    @Test
    void resumingWithBillNowStartsAFreshScheduleDueNow() {
        Subscription s = subscription(t("2026-10-14T00:00:00Z"), 0, "10.00", "0.00");
        s.setPeriodIndex(0);
        s.setGeneration(1);
        SubscriptionRules.Resumed r = SubscriptionRules.resumeAndBill(s, t("2026-12-03T08:00:00Z"));
        assertEquals(Subscription.State.ACTIVE, r.state());
        assertEquals(t("2026-12-03T08:00:00Z"), r.anchor());
        assertEquals(2, r.generation());
        assertEquals(-1, r.periodIndex());
        assertEquals(t("2026-12-03T08:00:00Z"), r.nextBillingAt());
        assertEquals(t("2027-01-03T08:00:00Z"), r.periodEnd());
    }
}
