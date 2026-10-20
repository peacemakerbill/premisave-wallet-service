package com.premisave.wallet.commercial.service.billing;

import com.premisave.wallet.commercial.entity.billing.Invoice;
import com.premisave.wallet.commercial.entity.billing.Subscription;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The state changes of an invoice and its attempts, as plain functions on an Invoice object so they can
 * be tested without a database. The services read an invoice, apply one of these and write it back with a
 * check on its version, so two writers cannot overwrite each other.
 */
public final class InvoiceRules {

    private InvoiceRules() {}

    /** What a failed attempt did to the invoice. */
    public record Failure(boolean changed, boolean counted, boolean exhausted, Instant nextRetryAt) {
        static Failure none() { return new Failure(false, false, false, null); }
    }

    public static Invoice.Attempt find(Invoice inv, int attemptNo) {
        for (Invoice.Attempt a : inv.getAttempts()) if (a.getAttempt() == attemptNo) return a;
        return null;
    }

    /** True while some attempt has not reached SUCCESS or FAILED. */
    public static boolean inFlight(Invoice inv) {
        for (Invoice.Attempt a : inv.getAttempts()) if (!a.isFinal()) return true;
        return false;
    }

    /** Failures that used up a retry; provider outages do not count. */
    public static int failedCount(Invoice inv) {
        int n = 0;
        for (Invoice.Attempt a : inv.getAttempts()) if (Invoice.Attempt.FAILED.equals(a.getStatus()) && a.isCounted()) n++;
        return n;
    }

    /** Opens the next attempt. The invoice is no longer waiting for a retry time while an attempt runs. */
    public static Invoice.Attempt begin(Invoice inv, String paymentMethodId, Instant now) {
        Invoice.Attempt a = new Invoice.Attempt(inv.getAttempts().size() + 1, now, paymentMethodId,
                inv.getStatus(), inv.getNextRetryAt());
        inv.getAttempts().add(a);
        inv.setNextRetryAt(null);
        inv.setStatus(Invoice.Status.OPEN);
        return a;
    }

    /** Undoes an attempt that never produced a payment, putting the invoice back as it was. */
    public static boolean discard(Invoice inv, int attemptNo) {
        Invoice.Attempt a = find(inv, attemptNo);
        if (a == null || !Invoice.Attempt.INITIATING.equals(a.getStatus())) return false;
        inv.getAttempts().remove(a);
        inv.setStatus(a.getPriorStatus() == null ? Invoice.Status.OPEN : a.getPriorStatus());
        inv.setNextRetryAt(a.getPriorNextRetryAt());
        return true;
    }

    /** Records that the payment exists and is waiting (PENDING) or needs the customer (REQUIRES_ACTION). */
    public static boolean attachPayment(Invoice inv, int attemptNo, String paymentId, boolean requiresAction) {
        Invoice.Attempt a = find(inv, attemptNo);
        if (a == null || a.isFinal()) return false;
        a.setPaymentId(paymentId);
        a.setStatus(requiresAction ? Invoice.Attempt.REQUIRES_ACTION : Invoice.Attempt.PENDING);
        if (inv.getStatus() == Invoice.Status.OPEN || inv.getStatus() == Invoice.Status.AWAITING_CUSTOMER) {
            inv.setStatus(requiresAction ? Invoice.Status.AWAITING_CUSTOMER : Invoice.Status.OPEN);
        }
        return true;
    }

    /** The payment succeeded. Money was collected, so the invoice is paid whatever it was before. */
    public static boolean success(Invoice inv, int attemptNo, String paymentId, long feeCents, Instant now) {
        Invoice.Attempt a = find(inv, attemptNo);
        if (a == null || a.isFinal()) return false;
        a.setStatus(Invoice.Attempt.SUCCESS);
        a.setPaymentId(paymentId);
        inv.setStatus(Invoice.Status.PAID);
        inv.setPaidAt(now);
        inv.setPaymentId(paymentId);
        inv.setFeeCents(feeCents);
        inv.setNextRetryAt(null);
        return true;
    }

    public static Failure failure(Invoice inv, int attemptNo, String paymentId, String code, Instant now) {
        Invoice.Attempt a = find(inv, attemptNo);
        if (a == null || a.isFinal()) return Failure.none();
        a.setStatus(Invoice.Attempt.FAILED);
        a.setCode(code);
        if (paymentId != null) a.setPaymentId(paymentId);
        boolean free = RetryPolicy.isTransientCode(code) && trailingTransient(inv) <= RetryPolicy.MAX_FREE_TRANSIENT;
        a.setCounted(!free);
        if (inv.getStatus() == Invoice.Status.PAID || inv.getStatus() == Invoice.Status.VOID) {
            return new Failure(true, !free, false, null);
        }
        if (free) {
            inv.setStatus(Invoice.Status.OPEN);
            inv.setNextRetryAt(now.plus(RetryPolicy.TRANSIENT_DELAY));
            return new Failure(true, false, false, inv.getNextRetryAt());
        }
        Instant next = RetryPolicy.nextRetryAt(inv.getDueAt(), inv.getRetryScheduleDays(), failedCount(inv), now);
        if (next != null) {
            inv.setStatus(Invoice.Status.OPEN);
            inv.setNextRetryAt(next);
            return new Failure(true, true, false, next);
        }
        inv.setStatus(Invoice.Status.PAYMENT_FAILED);
        inv.setNextRetryAt(null);
        return new Failure(true, true, true, null);
    }

    private static int trailingTransient(Invoice inv) {
        List<Invoice.Attempt> list = new ArrayList<>(inv.getAttempts());
        int n = 0;
        for (int i = list.size() - 1; i >= 0; i--) {
            Invoice.Attempt a = list.get(i);
            if (Invoice.Attempt.FAILED.equals(a.getStatus()) && RetryPolicy.isTransientCode(a.getCode())) n++;
            else break;
        }
        return n;
    }

    /**
     * Voids an invoice that is still waiting to be collected and that nothing is working on. An invoice
     * whose retries ran out stays PAYMENT_FAILED as the record of what the customer did not pay.
     */
    public static boolean voidInvoice(Invoice inv) {
        if (inv.getStatus() != Invoice.Status.OPEN) return false;
        if (inFlight(inv)) return false;
        inv.setStatus(Invoice.Status.VOID);
        inv.setNextRetryAt(null);
        return true;
    }

    /** Why a merchant cannot retry this invoice now, or null when they can. */
    public static String manualRetryBlock(Invoice inv, Subscription.State subscriptionState) {
        if (inv.getStatus() == Invoice.Status.PAID) return "This invoice is already paid";
        if (inv.getStatus() == Invoice.Status.VOID) return "This invoice was voided";
        if (!Subscription.RETRYABLE.contains(subscriptionState)) {
            return "The subscription is " + subscriptionState + "; its invoices can no longer be collected";
        }
        if (inFlight(inv)) return "A payment is still in progress for this invoice";
        return null;
    }
}
