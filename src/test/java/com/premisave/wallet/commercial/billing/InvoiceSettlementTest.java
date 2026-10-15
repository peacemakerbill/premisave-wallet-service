package com.premisave.wallet.commercial.billing;

import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.entity.TxResult;
import com.premisave.wallet.commercial.enums.ResultCode;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.enums.TxType;
import com.premisave.wallet.commercial.service.EventTypes;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Settlement drives the invoice and the subscription from a payment result. It is tested here with
 * in-memory stand-ins for the invoice store, the subscription lookup, the transitions and the events,
 * so the decisions (which state follows which result, and that a repeat changes nothing) are checked
 * without a database.
 */
class InvoiceSettlementTest {

    /** Far enough ahead that the one hour minimum gap between a failure and its retry never applies. */
    private static final Instant DUE = Instant.now().plus(java.time.Duration.ofDays(30)).truncatedTo(java.time.temporal.ChronoUnit.DAYS);

    /** Invoices held in a map; mutate behaves like the real one without the version check. */
    private static class MemoryInvoices extends InvoiceStore {
        final Map<String, Invoice> byId = new HashMap<>();

        MemoryInvoices() { super(null, null); }

        @Override public Optional<Invoice> find(String id) { return Optional.ofNullable(byId.get(id)); }

        @Override public Optional<Invoice> mutate(String id, Predicate<Invoice> change) {
            Invoice inv = byId.get(id);
            if (inv == null || !change.test(inv)) return Optional.empty();
            inv.setVersion(inv.getVersion() + 1);
            return Optional.of(inv);
        }
    }

    private static class Calls extends SubscriptionTransitions {
        final List<String> log = new ArrayList<>();

        Calls() { super(null, null, null, null); }

        @Override public Optional<Subscription> markPastDue(String id) { log.add("pastDue"); return Optional.empty(); }

        @Override public Optional<Subscription> markRecovered(String id) { log.add("recovered"); return Optional.empty(); }

        @Override public Optional<Subscription> terminate(String id, Subscription.State target, String reason, Instant now) {
            log.add("terminate:" + target);
            return Optional.empty();
        }
    }

    private static class Events extends BillingEvents {
        final List<String> log = new ArrayList<>();

        Events() { super(null); }

        @Override public void invoice(String type, Invoice inv, Subscription s, int attemptNo) { log.add(type + "#" + attemptNo); }

        @Override public void subscription(String type, Subscription s) { log.add(type); }
    }

    private final MemoryInvoices invoices = new MemoryInvoices();
    private final Calls transitions = new Calls();
    private final Events events = new Events();
    private Subscription sub = subscription(Subscription.State.ACTIVE);

    private final BillingSubscriptionRepository subs = (BillingSubscriptionRepository) Proxy.newProxyInstance(
            BillingSubscriptionRepository.class.getClassLoader(), new Class<?>[]{BillingSubscriptionRepository.class},
            (proxy, method, args) -> method.getName().equals("findById") ? Optional.of(sub) : null);

    private final InvoiceSettlement settlement = new InvoiceSettlement(invoices, subs, transitions, events);

    private static Subscription subscription(Subscription.State state) {
        Subscription s = new Subscription();
        s.setId("sub_1");
        s.setState(state);
        return s;
    }

    private Invoice invoiceWithAttempt(List<Integer> schedule, Plan.OnExhausted onExhausted) {
        Invoice inv = new Invoice();
        inv.setId("inv_1");
        inv.setSubscriptionId("sub_1");
        inv.setDueAt(DUE);
        inv.setNextRetryAt(DUE);
        inv.setRetryScheduleDays(schedule);
        inv.setOnExhausted(onExhausted);
        InvoiceRules.begin(inv, "pm_1", DUE);
        invoices.byId.put(inv.getId(), inv);
        return inv;
    }

    private static CommercialTransaction payment(TxStatus status, ResultCode code, int attempt) {
        CommercialTransaction tx = new CommercialTransaction();
        tx.setId("pay_" + attempt);
        tx.setType(TxType.PAYMENT);
        tx.setStatus(status);
        tx.setFeeCents(45);
        if (code != null) tx.setResult(new TxResult(code, "x", null, null));
        tx.getRelations().put(InvoiceSettlement.RELATION_INVOICE, "inv_1");
        tx.getRelations().put(InvoiceSettlement.RELATION_ATTEMPT, String.valueOf(attempt));
        return tx;
    }

    @Test
    void aSuccessfulPaymentPaysTheInvoiceAndReportsIt() {
        Invoice inv = invoiceWithAttempt(List.of(1, 3), Plan.OnExhausted.CANCEL);
        settlement.onPaymentFinished(payment(TxStatus.SUCCESS, ResultCode.SUCCESS, 1));
        assertEquals(Invoice.Status.PAID, inv.getStatus());
        assertEquals("pay_1", inv.getPaymentId());
        assertEquals(45, inv.getFeeCents());
        assertEquals(List.of(EventTypes.INVOICE_PAID + "#1"), events.log);
        assertTrue(transitions.log.isEmpty());
    }

    @Test
    void payingAPastDueSubscriptionsInvoiceRecoversIt() {
        sub = subscription(Subscription.State.PAST_DUE);
        invoiceWithAttempt(List.of(1), Plan.OnExhausted.CANCEL);
        settlement.onPaymentFinished(payment(TxStatus.SUCCESS, ResultCode.SUCCESS, 1));
        assertEquals(List.of("recovered"), transitions.log);
    }

    @Test
    void theSamePaymentSettledTwiceChangesNothingTheSecondTime() {
        invoiceWithAttempt(List.of(1), Plan.OnExhausted.CANCEL);
        CommercialTransaction tx = payment(TxStatus.SUCCESS, ResultCode.SUCCESS, 1);
        settlement.onPaymentFinished(tx);
        settlement.onPaymentFinished(tx);
        assertEquals(1, events.log.size());
    }

    @Test
    void aDeclineSchedulesARetryAndMakesTheSubscriptionPastDue() {
        Invoice inv = invoiceWithAttempt(List.of(1, 3), Plan.OnExhausted.CANCEL);
        settlement.onPaymentFinished(payment(TxStatus.FAILED, ResultCode.DECLINED, 1));
        assertEquals(Invoice.Status.OPEN, inv.getStatus());
        assertEquals(DUE.plus(java.time.Duration.ofDays(1)), inv.getNextRetryAt());
        assertEquals(List.of(EventTypes.INVOICE_PAYMENT_FAILED + "#1"), events.log);
        assertEquals(List.of("pastDue"), transitions.log);
        assertEquals("DECLINED", inv.getAttempts().get(0).getCode());
    }

    @Test
    void theLastFailureEndsTheSubscriptionAsConfigured() {
        Invoice cancel = invoiceWithAttempt(List.of(), Plan.OnExhausted.CANCEL);
        settlement.onPaymentFinished(payment(TxStatus.FAILED, ResultCode.DECLINED, 1));
        assertEquals(Invoice.Status.PAYMENT_FAILED, cancel.getStatus());
        assertEquals(List.of("terminate:CANCELLED"), transitions.log);

        transitions.log.clear();
        invoices.byId.clear();
        Invoice expire = invoiceWithAttempt(List.of(), Plan.OnExhausted.EXPIRE);
        settlement.onPaymentFinished(payment(TxStatus.FAILED, ResultCode.DECLINED, 1));
        assertEquals(Invoice.Status.PAYMENT_FAILED, expire.getStatus());
        assertEquals(List.of("terminate:EXPIRED"), transitions.log);
    }

    @Test
    void aTimedOutPaymentCountsAsAFailureWithItsOwnCode() {
        Invoice inv = invoiceWithAttempt(List.of(1), Plan.OnExhausted.CANCEL);
        settlement.onPaymentFinished(payment(TxStatus.TIMED_OUT, ResultCode.CUSTOMER_TIMEOUT, 1));
        assertEquals("CUSTOMER_TIMEOUT", inv.getAttempts().get(0).getCode());
        assertEquals(List.of("pastDue"), transitions.log);
    }

    @Test
    void aResultWithoutACodeFallsBackToTheStatus() {
        Invoice inv = invoiceWithAttempt(List.of(1), Plan.OnExhausted.CANCEL);
        settlement.onPaymentFinished(payment(TxStatus.CANCELLED, null, 1));
        assertEquals("CANCELLED", inv.getAttempts().get(0).getCode());
    }

    @Test
    void aProviderOutageIsReportedButDoesNotChangeTheSubscription() {
        Invoice inv = invoiceWithAttempt(List.of(1), Plan.OnExhausted.CANCEL);
        settlement.onPaymentFinished(payment(TxStatus.FAILED, ResultCode.PROVIDER_ERROR, 1));
        assertEquals(Invoice.Status.OPEN, inv.getStatus());
        Instant soon = Instant.now();
        assertTrue(inv.getNextRetryAt().isAfter(soon.plus(java.time.Duration.ofMinutes(14))), "retried in about 15 minutes");
        assertTrue(inv.getNextRetryAt().isBefore(soon.plus(java.time.Duration.ofMinutes(16))), "retried in about 15 minutes");
        assertEquals(List.of(EventTypes.INVOICE_PAYMENT_FAILED + "#1"), events.log);
        assertTrue(transitions.log.isEmpty());
        assertEquals(0, InvoiceRules.failedCount(inv));
    }

    @Test
    void aFailureOnAnAlreadyPaidInvoiceDoesNotTouchTheSubscription() {
        Invoice inv = invoiceWithAttempt(List.of(1), Plan.OnExhausted.CANCEL);
        inv.setStatus(Invoice.Status.PAID);
        settlement.onPaymentFinished(payment(TxStatus.FAILED, ResultCode.DECLINED, 1));
        assertEquals(Invoice.Status.PAID, inv.getStatus());
        assertTrue(transitions.log.isEmpty());
    }

    @Test
    void paymentsThatBelongToNoInvoiceAreIgnored() {
        invoiceWithAttempt(List.of(1), Plan.OnExhausted.CANCEL);
        CommercialTransaction plain = new CommercialTransaction();
        plain.setId("pay_x");
        plain.setType(TxType.PAYMENT);
        plain.setStatus(TxStatus.SUCCESS);
        settlement.onPaymentFinished(plain);
        assertTrue(events.log.isEmpty());
        assertFalse(InvoiceSettlement.isInvoicePayment(plain));
    }

    @Test
    void anUnreadableAttemptNumberIsIgnored() {
        invoiceWithAttempt(List.of(1), Plan.OnExhausted.CANCEL);
        CommercialTransaction tx = payment(TxStatus.SUCCESS, ResultCode.SUCCESS, 1);
        tx.getRelations().put(InvoiceSettlement.RELATION_ATTEMPT, "one");
        settlement.onPaymentFinished(tx);
        assertTrue(events.log.isEmpty());
    }

    @Test
    void anOpenPaymentIsNotSettled() {
        Invoice inv = invoiceWithAttempt(List.of(1), Plan.OnExhausted.CANCEL);
        settlement.onPaymentFinished(payment(TxStatus.PENDING, null, 1));
        assertEquals(Invoice.Attempt.INITIATING, inv.getAttempts().get(0).getStatus());
        assertTrue(events.log.isEmpty());
    }
}
