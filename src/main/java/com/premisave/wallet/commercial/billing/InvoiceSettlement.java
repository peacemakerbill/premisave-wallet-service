package com.premisave.wallet.commercial.billing;

import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.service.EventTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * What happens to an invoice and its subscription when an attempt's payment reaches a result. Called by
 * the payment listener when a payment finishes, by the billing engine when a payment fails before it
 * even exists, and by the recovery sweep. It is safe to call any number of times for the same attempt:
 * only the first call changes anything.
 */
@Component("commercialInvoiceSettlement")
public class InvoiceSettlement {

    private static final Logger log = LoggerFactory.getLogger(InvoiceSettlement.class);

    public static final String RELATION_INVOICE = "invoiceId";
    public static final String RELATION_ATTEMPT = "attempt";
    public static final String RELATION_SUBSCRIPTION = "subscriptionId";

    private final InvoiceStore invoices;
    private final BillingSubscriptionRepository subs;
    private final SubscriptionTransitions transitions;
    private final BillingEvents events;

    public InvoiceSettlement(InvoiceStore invoices, BillingSubscriptionRepository subs, SubscriptionTransitions transitions,
                             BillingEvents events) {
        this.invoices = invoices;
        this.subs = subs;
        this.transitions = transitions;
        this.events = events;
    }

    /** True for a payment that was made for an invoice attempt. */
    public static boolean isInvoicePayment(CommercialTransaction tx) {
        return tx.getRelations().containsKey(RELATION_INVOICE) && tx.getRelations().containsKey(RELATION_ATTEMPT);
    }

    public void onPaymentFinished(CommercialTransaction tx) {
        if (!isInvoicePayment(tx)) return;
        String invoiceId = tx.getRelations().get(RELATION_INVOICE);
        int attempt;
        try {
            attempt = Integer.parseInt(tx.getRelations().get(RELATION_ATTEMPT));
        } catch (NumberFormatException e) {
            log.warn("Payment {} has an unreadable invoice attempt number", tx.getId());
            return;
        }
        Instant now = Instant.now();
        switch (tx.getStatus()) {
            case SUCCESS -> paid(invoiceId, attempt, tx.getId(), tx.getFeeCents(), now);
            case FAILED, TIMED_OUT, CANCELLED -> failed(invoiceId, attempt, tx.getId(), codeOf(tx), now);
            default -> { }
        }
    }

    /** The provider-neutral reason, falling back to the status when the rail gave none. */
    public static String codeOf(CommercialTransaction tx) {
        if (tx.getResult() != null && tx.getResult().getCode() != null) return tx.getResult().getCode().name();
        return tx.getStatus() == TxStatus.TIMED_OUT ? "TIMED_OUT" : tx.getStatus().name();
    }

    public void paid(String invoiceId, int attempt, String paymentId, long feeCents, Instant now) {
        invoices.mutate(invoiceId, inv -> InvoiceRules.success(inv, attempt, paymentId, feeCents, now)).ifPresent(inv -> {
            Subscription s = subs.findById(inv.getSubscriptionId()).orElse(null);
            if (s == null) return;
            events.invoice(EventTypes.INVOICE_PAID, inv, s, attempt);
            if (s.getState() == Subscription.State.PAST_DUE) transitions.markRecovered(s.getId());
        });
    }

    public void failed(String invoiceId, int attempt, String paymentId, String code, Instant now) {
        AtomicReference<InvoiceRules.Failure> outcome = new AtomicReference<>();
        invoices.mutate(invoiceId, inv -> {
            InvoiceRules.Failure f = InvoiceRules.failure(inv, attempt, paymentId, code, now);
            outcome.set(f);
            return f.changed();
        }).ifPresent(inv -> {
            Subscription s = subs.findById(inv.getSubscriptionId()).orElse(null);
            if (s == null) return;
            events.invoice(EventTypes.INVOICE_PAYMENT_FAILED, inv, s, attempt);
            boolean unpaid = inv.getStatus() == Invoice.Status.OPEN || inv.getStatus() == Invoice.Status.PAYMENT_FAILED;
            InvoiceRules.Failure f = outcome.get();
            if (!unpaid || f == null) return;
            if (f.exhausted()) {
                Subscription.State end = inv.getOnExhausted() == Plan.OnExhausted.EXPIRE
                        ? Subscription.State.EXPIRED : Subscription.State.CANCELLED;
                transitions.terminate(s.getId(), end, "Payment failed after all retries", now);
            } else if (f.counted()) {
                transitions.markPastDue(s.getId());
            }
        });
    }
}
