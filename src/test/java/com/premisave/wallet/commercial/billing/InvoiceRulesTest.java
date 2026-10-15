package com.premisave.wallet.commercial.billing;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvoiceRulesTest {

    private static final Instant DUE = Instant.parse("2026-10-14T00:00:00Z");

    private static Invoice invoice(int... schedule) {
        Invoice inv = new Invoice();
        inv.setId("inv_1");
        inv.setDueAt(DUE);
        inv.setNextRetryAt(DUE);
        List<Integer> days = new ArrayList<>();
        for (int d : schedule) days.add(d);
        inv.setRetryScheduleDays(days);
        return inv;
    }

    @Test
    void beginningAnAttemptTakesTheInvoiceOutOfTheRetryQueue() {
        Invoice inv = invoice(1, 3);
        Invoice.Attempt a = InvoiceRules.begin(inv, "pm_1", DUE);
        assertEquals(1, a.getAttempt());
        assertEquals(Invoice.Attempt.INITIATING, a.getStatus());
        assertNull(inv.getNextRetryAt());
        assertTrue(InvoiceRules.inFlight(inv));
    }

    @Test
    void discardingAnAttemptRestoresTheInvoice() {
        Invoice inv = invoice(1);
        InvoiceRules.begin(inv, "pm_1", DUE);
        assertTrue(InvoiceRules.discard(inv, 1));
        assertEquals(0, inv.getAttempts().size());
        assertEquals(DUE, inv.getNextRetryAt());
        assertEquals(Invoice.Status.OPEN, inv.getStatus());
    }

    @Test
    void onlyAnAttemptWithoutAPaymentCanBeDiscarded() {
        Invoice inv = invoice(1);
        InvoiceRules.begin(inv, "pm_1", DUE);
        InvoiceRules.attachPayment(inv, 1, "pay_1", false);
        assertFalse(InvoiceRules.discard(inv, 1));
    }

    @Test
    void successPaysTheInvoiceOnce() {
        Invoice inv = invoice(1);
        InvoiceRules.begin(inv, "pm_1", DUE);
        assertTrue(InvoiceRules.success(inv, 1, "pay_1", 45, DUE.plusSeconds(5)));
        assertEquals(Invoice.Status.PAID, inv.getStatus());
        assertEquals("pay_1", inv.getPaymentId());
        assertEquals(45, inv.getFeeCents());
        assertNotNull(inv.getPaidAt());
        assertFalse(InvoiceRules.success(inv, 1, "pay_1", 45, DUE.plusSeconds(9)));
        assertFalse(InvoiceRules.inFlight(inv));
    }

    @Test
    void requiresActionMakesTheInvoiceAwaitTheCustomer() {
        Invoice inv = invoice(1);
        InvoiceRules.begin(inv, "pm_1", DUE);
        InvoiceRules.attachPayment(inv, 1, "pay_1", true);
        assertEquals(Invoice.Status.AWAITING_CUSTOMER, inv.getStatus());
        assertEquals(Invoice.Attempt.REQUIRES_ACTION, inv.getAttempts().get(0).getStatus());
    }

    @Test
    void aDeclineSchedulesTheNextRetryThenExhausts() {
        Invoice inv = invoice(1, 3);
        InvoiceRules.begin(inv, "pm_1", DUE);
        InvoiceRules.Failure f1 = InvoiceRules.failure(inv, 1, "pay_1", "DECLINED", DUE.plusSeconds(10));
        assertTrue(f1.changed());
        assertTrue(f1.counted());
        assertFalse(f1.exhausted());
        assertEquals(Instant.parse("2026-10-15T00:00:00Z"), inv.getNextRetryAt());
        assertEquals(Invoice.Status.OPEN, inv.getStatus());

        InvoiceRules.begin(inv, "pm_1", inv.getNextRetryAt());
        InvoiceRules.Failure f2 = InvoiceRules.failure(inv, 2, "pay_2", "DECLINED", Instant.parse("2026-10-15T00:00:05Z"));
        assertEquals(Instant.parse("2026-10-17T00:00:00Z"), f2.nextRetryAt());

        InvoiceRules.begin(inv, "pm_1", inv.getNextRetryAt());
        InvoiceRules.Failure f3 = InvoiceRules.failure(inv, 3, "pay_3", "DECLINED", Instant.parse("2026-10-17T00:00:05Z"));
        assertTrue(f3.exhausted());
        assertEquals(Invoice.Status.PAYMENT_FAILED, inv.getStatus());
        assertNull(inv.getNextRetryAt());
        assertEquals(3, InvoiceRules.failedCount(inv));
    }

    @Test
    void aProviderOutageDoesNotUseARetry() {
        Invoice inv = invoice(1);
        InvoiceRules.begin(inv, "pm_1", DUE);
        InvoiceRules.Failure f = InvoiceRules.failure(inv, 1, "pay_1", "PROVIDER_ERROR", DUE);
        assertFalse(f.counted());
        assertFalse(f.exhausted());
        assertEquals(DUE.plus(RetryPolicy.TRANSIENT_DELAY), inv.getNextRetryAt());
        assertEquals(0, InvoiceRules.failedCount(inv));
    }

    @Test
    void repeatedOutagesEventuallyCount() {
        Invoice inv = invoice();
        Instant now = DUE;
        boolean exhausted = false;
        for (int i = 1; i <= RetryPolicy.MAX_FREE_TRANSIENT + 1; i++) {
            InvoiceRules.begin(inv, "pm_1", now);
            exhausted = InvoiceRules.failure(inv, i, "pay_" + i, "PROVIDER_ERROR", now).exhausted();
            now = now.plus(RetryPolicy.TRANSIENT_DELAY);
        }
        assertTrue(exhausted);
        assertEquals(1, InvoiceRules.failedCount(inv));
        assertEquals(Invoice.Status.PAYMENT_FAILED, inv.getStatus());
    }

    @Test
    void aFinishedAttemptIsNotChangedAgain() {
        Invoice inv = invoice(1);
        InvoiceRules.begin(inv, "pm_1", DUE);
        InvoiceRules.failure(inv, 1, "pay_1", "DECLINED", DUE);
        InvoiceRules.Failure again = InvoiceRules.failure(inv, 1, "pay_1", "DECLINED", DUE);
        assertFalse(again.changed());
        assertEquals(1, InvoiceRules.failedCount(inv));
        assertFalse(InvoiceRules.success(inv, 1, "pay_1", 0, DUE));
    }

    @Test
    void aLatePaymentOnAVoidedInvoiceStillCountsAsPaid() {
        Invoice inv = invoice(1);
        InvoiceRules.begin(inv, "pm_1", DUE);
        inv.setStatus(Invoice.Status.VOID);
        assertTrue(InvoiceRules.success(inv, 1, "pay_1", 10, DUE));
        assertEquals(Invoice.Status.PAID, inv.getStatus());
    }

    @Test
    void aFailureOnAVoidedInvoiceChangesNothingElse() {
        Invoice inv = invoice(1);
        InvoiceRules.begin(inv, "pm_1", DUE);
        inv.setStatus(Invoice.Status.VOID);
        InvoiceRules.failure(inv, 1, "pay_1", "DECLINED", DUE);
        assertEquals(Invoice.Status.VOID, inv.getStatus());
        assertNull(inv.getNextRetryAt());
    }

    @Test
    void voidingNeedsNothingInFlight() {
        Invoice inv = invoice(1);
        assertTrue(InvoiceRules.voidInvoice(inv));
        assertEquals(Invoice.Status.VOID, inv.getStatus());

        Invoice busy = invoice(1);
        InvoiceRules.begin(busy, "pm_1", DUE);
        assertFalse(InvoiceRules.voidInvoice(busy));

        Invoice paid = invoice(1);
        paid.setStatus(Invoice.Status.PAID);
        assertFalse(InvoiceRules.voidInvoice(paid));

        Invoice exhausted = invoice(1);
        exhausted.setStatus(Invoice.Status.PAYMENT_FAILED);
        assertFalse(InvoiceRules.voidInvoice(exhausted));
    }

    @Test
    void manualRetryRules() {
        Invoice inv = invoice(1);
        assertNull(InvoiceRules.manualRetryBlock(inv, Subscription.State.ACTIVE));
        assertNull(InvoiceRules.manualRetryBlock(inv, Subscription.State.PAST_DUE));
        assertNotNull(InvoiceRules.manualRetryBlock(inv, Subscription.State.PAUSED));
        assertNotNull(InvoiceRules.manualRetryBlock(inv, Subscription.State.CANCELLED));

        inv.setStatus(Invoice.Status.PAID);
        assertNotNull(InvoiceRules.manualRetryBlock(inv, Subscription.State.ACTIVE));
        inv.setStatus(Invoice.Status.VOID);
        assertNotNull(InvoiceRules.manualRetryBlock(inv, Subscription.State.ACTIVE));

        Invoice busy = invoice(1);
        InvoiceRules.begin(busy, "pm_1", DUE);
        assertNotNull(InvoiceRules.manualRetryBlock(busy, Subscription.State.ACTIVE));
    }

    @Test
    void anExhaustedInvoiceCanBeReopenedByAnAttempt() {
        Invoice inv = invoice();
        InvoiceRules.begin(inv, "pm_1", DUE);
        assertTrue(InvoiceRules.failure(inv, 1, "pay_1", "DECLINED", DUE).exhausted());
        assertEquals(Invoice.Status.PAYMENT_FAILED, inv.getStatus());
        InvoiceRules.begin(inv, "pm_2", DUE);
        assertEquals(Invoice.Status.OPEN, inv.getStatus());
        assertTrue(InvoiceRules.discard(inv, 2));
        assertEquals(Invoice.Status.PAYMENT_FAILED, inv.getStatus());
    }
}
