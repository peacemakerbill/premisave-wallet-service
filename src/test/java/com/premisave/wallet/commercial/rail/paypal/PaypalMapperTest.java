package com.premisave.wallet.commercial.rail.paypal;

import com.premisave.wallet.commercial.enums.ResultCode;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.rail.RailOutcome;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PaypalMapperTest {

    private static Map<String, Object> completed(String captureStatus) {
        return Map.of("status", "COMPLETED", "purchase_units",
                List.of(Map.of("payments", Map.of("captures", List.of(Map.of("id", "CAP1", "status", captureStatus))))));
    }

    @Test
    void amountsAreFormattedForPaypal() {
        assertEquals("10.50", PaypalMapper.value(new BigDecimal("10.5"), "USD"));
        assertEquals("1000", PaypalMapper.value(new BigDecimal("999.6"), "JPY"));
        assertEquals("0.01", PaypalMapper.value(new BigDecimal("0.005"), "usd"));
    }

    @Test
    void completedOrderWithCompletedCaptureIsPaid() {
        PaypalMapper.OrderState s = PaypalMapper.order(completed("COMPLETED"));
        assertEquals(PaypalMapper.Kind.COMPLETED, s.kind());
        assertEquals("CAP1", s.captureId());
        assertEquals(TxStatus.SUCCESS, PaypalMapper.outcome(s, "ORD1").status());
    }

    @Test
    void pendingCaptureKeepsWaiting() {
        PaypalMapper.OrderState s = PaypalMapper.order(completed("PENDING"));
        assertEquals(PaypalMapper.Kind.WAITING, s.kind());
        assertNull(PaypalMapper.outcome(s, "ORD1"));
    }

    @Test
    void declinedCaptureFails() {
        assertEquals(TxStatus.FAILED, PaypalMapper.outcome(PaypalMapper.order(completed("DECLINED")), "ORD1").status());
    }

    @Test
    void approvedOrderStillNeedsCapturing() {
        PaypalMapper.OrderState s = PaypalMapper.order(Map.of("status", "APPROVED"));
        assertEquals(PaypalMapper.Kind.APPROVED, s.kind());
        assertNull(PaypalMapper.outcome(s, "ORD1"));
    }

    @Test
    void voidedOrderIsCancelledByTheCustomer() {
        RailOutcome o = PaypalMapper.outcome(PaypalMapper.order(Map.of("status", "VOIDED")), "ORD1");
        assertEquals(ResultCode.CUSTOMER_CANCELLED, o.code());
    }

    @Test
    void createdOrderKeepsWaiting() {
        assertEquals(PaypalMapper.Kind.WAITING, PaypalMapper.order(Map.of("status", "CREATED")).kind());
        assertEquals(PaypalMapper.Kind.WAITING, PaypalMapper.order(Map.of("status", "PAYER_ACTION_REQUIRED")).kind());
    }

    @Test
    void errorsReadTheFirstIssue() {
        Map<String, Object> body = Map.of("message", "Unprocessable", "details", List.of(Map.of("issue", "INSTRUMENT_DECLINED")));
        assertEquals("INSTRUMENT_DECLINED", PaypalMapper.firstIssue(body));
        assertEquals(ResultCode.DECLINED, PaypalMapper.errorCode(body));
        assertEquals("PayPal refused the request (INSTRUMENT_DECLINED)", PaypalMapper.errorMessage(body, 422));
        assertEquals(ResultCode.INSUFFICIENT_FUNDS,
                PaypalMapper.errorCode(Map.of("details", List.of(Map.of("issue", "INSUFFICIENT_FUNDS")))));
        assertEquals("PayPal answered 500", PaypalMapper.errorMessage(Map.of(), 500));
        assertEquals("", PaypalMapper.firstIssue(null));
    }

    @Test
    void linkPicksTheFirstRelationThatExists() {
        Map<String, Object> body = Map.of("links", List.of(
                Map.of("rel", "self", "href", "https://api/self"),
                Map.of("rel", "approve", "href", "https://pay/approve")));
        assertEquals("https://pay/approve", PaypalMapper.link(body, "payer-action", "approve"));
        assertNull(PaypalMapper.link(body, "missing"));
        assertNull(PaypalMapper.link(null, "approve"));
    }

    @Test
    void refundStates() {
        assertEquals(TxStatus.SUCCESS, PaypalMapper.refund(Map.of("status", "COMPLETED")).status());
        assertEquals(TxStatus.FAILED, PaypalMapper.refund(Map.of("status", "FAILED")).status());
        assertNull(PaypalMapper.refund(Map.of("status", "PENDING")));
    }

    @Test
    void payoutItemStates() {
        assertEquals(TxStatus.SUCCESS, PaypalMapper.payout(Map.of("items", List.of(Map.of("transaction_status", "SUCCESS"))), "B1").status());
        assertEquals(TxStatus.FAILED, PaypalMapper.payout(Map.of("items", List.of(Map.of("transaction_status", "BLOCKED"))), "B1").status());
        assertEquals(ResultCode.REVERSED,
                PaypalMapper.payout(Map.of("items", List.of(Map.of("transaction_status", "RETURNED"))), "B1").code());
        assertNull(PaypalMapper.payout(Map.of("items", List.of(Map.of("transaction_status", "UNCLAIMED"))), "B1"));
        assertNull(PaypalMapper.payout(Map.of("items", List.of(Map.of("transaction_status", "PENDING"))), "B1"));
    }

    @Test
    void deniedBatchWithoutItemsFails() {
        assertEquals(TxStatus.FAILED, PaypalMapper.payout(Map.of("batch_header", Map.of("batch_status", "DENIED")), "B1").status());
        assertNull(PaypalMapper.payout(Map.of("batch_header", Map.of("batch_status", "PENDING")), "B1"));
    }
}
