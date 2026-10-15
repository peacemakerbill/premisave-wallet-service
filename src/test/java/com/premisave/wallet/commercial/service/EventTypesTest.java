package com.premisave.wallet.commercial.service;

import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.enums.TxType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventTypesTest {

    private static String type(TxType t, TxStatus s) {
        CommercialTransaction tx = new CommercialTransaction();
        tx.setType(t);
        tx.setStatus(s);
        return EventTypes.forTransaction(tx);
    }

    @Test
    void paymentEvents() {
        assertEquals("payment.requires_action", type(TxType.PAYMENT, TxStatus.REQUIRES_ACTION));
        assertEquals("payment.succeeded", type(TxType.PAYMENT, TxStatus.SUCCESS));
        assertEquals("payment.failed", type(TxType.PAYMENT, TxStatus.FAILED));
        assertEquals("payment.timed_out", type(TxType.PAYMENT, TxStatus.TIMED_OUT));
        assertEquals("payment.cancelled", type(TxType.PAYMENT, TxStatus.CANCELLED));
        assertNull(type(TxType.PAYMENT, TxStatus.REVERSED));
    }

    @Test
    void payoutEvents() {
        assertEquals("payout.succeeded", type(TxType.PAYOUT, TxStatus.SUCCESS));
        assertEquals("payout.failed", type(TxType.PAYOUT, TxStatus.FAILED));
        assertEquals("payout.failed", type(TxType.PAYOUT, TxStatus.REVERSED));
        assertEquals("payout.timed_out", type(TxType.PAYOUT, TxStatus.TIMED_OUT));
    }

    @Test
    void refundEvents() {
        assertEquals("refund.succeeded", type(TxType.REFUND, TxStatus.SUCCESS));
        assertEquals("refund.failed", type(TxType.REFUND, TxStatus.FAILED));
        assertEquals("refund.failed", type(TxType.REFUND, TxStatus.TIMED_OUT));
        assertEquals("refund.failed", type(TxType.REFUND, TxStatus.REVERSED));
        assertNull(type(TxType.REFUND, TxStatus.PENDING));
    }

    @Test
    void settlementEvents() {
        assertEquals("settlement.succeeded", type(TxType.SETTLEMENT, TxStatus.SUCCESS));
        assertEquals("settlement.failed", type(TxType.SETTLEMENT, TxStatus.FAILED));
        assertEquals("settlement.failed", type(TxType.SETTLEMENT, TxStatus.TIMED_OUT));
        assertEquals("settlement.failed", type(TxType.SETTLEMENT, TxStatus.REVERSED));
        assertNull(type(TxType.SETTLEMENT, TxStatus.PENDING));
    }

    @Test
    void transfersRaiseNoEvent() {
        assertNull(type(TxType.TRANSFER, TxStatus.SUCCESS));
        assertNull(type(TxType.TRANSFER, TxStatus.FAILED));
    }

    @Test
    void billingEventNamesMatchTheEndpointReference() {
        assertEquals("subscription.created", EventTypes.SUBSCRIPTION_CREATED);
        assertEquals("subscription.activated", EventTypes.SUBSCRIPTION_ACTIVATED);
        assertEquals("subscription.past_due", EventTypes.SUBSCRIPTION_PAST_DUE);
        assertEquals("subscription.paused", EventTypes.SUBSCRIPTION_PAUSED);
        assertEquals("subscription.cancelled", EventTypes.SUBSCRIPTION_CANCELLED);
        assertEquals("customer.payment_method.activated", EventTypes.PAYMENT_METHOD_ACTIVATED);
        assertEquals("invoice.upcoming", EventTypes.INVOICE_UPCOMING);
        assertEquals("invoice.paid", EventTypes.INVOICE_PAID);
        assertEquals("invoice.payment_failed", EventTypes.INVOICE_PAYMENT_FAILED);
    }

    @Test
    void billingEventsCanBeSubscribedToByPattern() {
        assertTrue(com.premisave.wallet.commercial.util.EventTypeMatcher.isValidPattern(EventTypes.PAYMENT_METHOD_ACTIVATED));
        assertTrue(com.premisave.wallet.commercial.util.EventTypeMatcher.matches("subscription.*", EventTypes.SUBSCRIPTION_PAST_DUE));
        assertTrue(com.premisave.wallet.commercial.util.EventTypeMatcher.matches("invoice.*", EventTypes.INVOICE_PAID));
        assertTrue(com.premisave.wallet.commercial.util.EventTypeMatcher.matches("customer.*", EventTypes.PAYMENT_METHOD_ACTIVATED));
    }
}
