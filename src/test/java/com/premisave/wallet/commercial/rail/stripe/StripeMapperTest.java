package com.premisave.wallet.commercial.rail.stripe;

import com.premisave.wallet.commercial.enums.ResultCode;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.rail.RailOutcome;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StripeMapperTest {

    @Test
    void succeededIntentIsPaid() {
        StripeMapper.IntentState s = StripeMapper.intent(Map.of("status", "succeeded"));
        assertEquals(StripeMapper.Verdict.SUCCESS, s.verdict());
        RailOutcome o = StripeMapper.outcome(s, "pi_1");
        assertEquals(TxStatus.SUCCESS, o.status());
        assertEquals("pi_1", o.providerReference());
    }

    @Test
    void intentWithoutAnErrorKeepsWaiting() {
        assertEquals(StripeMapper.Verdict.WAITING, StripeMapper.intent(Map.of("status", "requires_payment_method")).verdict());
        assertEquals(StripeMapper.Verdict.WAITING, StripeMapper.intent(Map.of("status", "requires_action")).verdict());
        assertEquals(StripeMapper.Verdict.WAITING, StripeMapper.intent(Map.of("status", "processing")).verdict());
        assertNull(StripeMapper.outcome(StripeMapper.intent(Map.of("status", "processing")), "pi_1"));
    }

    @Test
    void failedPaymentAttemptCarriesTheDeclineReason() {
        Map<String, Object> pi = Map.of("status", "requires_payment_method",
                "last_payment_error", Map.of("code", "card_declined", "decline_code", "insufficient_funds", "message", "Not enough"));
        StripeMapper.IntentState s = StripeMapper.intent(pi);
        assertEquals(StripeMapper.Verdict.FAILED, s.verdict());
        assertEquals(ResultCode.INSUFFICIENT_FUNDS, s.code());
        assertEquals("Not enough", s.description());
        assertEquals("insufficient_funds", s.providerCode());
        assertEquals(TxStatus.FAILED, StripeMapper.outcome(s, "pi_1").status());
    }

    @Test
    void cancelledIntentIsCancelled() {
        StripeMapper.IntentState s = StripeMapper.intent(Map.of("status", "canceled", "cancellation_reason", "abandoned"));
        assertEquals(TxStatus.CANCELLED, StripeMapper.outcome(s, "pi_1").status());
        assertEquals(ResultCode.CUSTOMER_CANCELLED, s.code());
    }

    @Test
    void declineCodesMap() {
        assertEquals(ResultCode.INSUFFICIENT_FUNDS, StripeMapper.declineCode("card_declined", "insufficient_funds"));
        assertEquals(ResultCode.PROVIDER_ERROR, StripeMapper.declineCode("processing_error", null));
        assertEquals(ResultCode.DECLINED, StripeMapper.declineCode("card_declined", "do_not_honor"));
        assertEquals(ResultCode.DECLINED, StripeMapper.declineCode(null, null));
    }

    @Test
    void refundStates() {
        assertEquals(TxStatus.SUCCESS, StripeMapper.refund(Map.of("status", "succeeded", "id", "re_1")).status());
        assertEquals(TxStatus.FAILED, StripeMapper.refund(Map.of("status", "failed", "failure_reason", "expired_or_canceled_card")).status());
        assertNull(StripeMapper.refund(Map.of("status", "pending")));
        assertNull(StripeMapper.refund(Map.of("status", "requires_action")));
    }

    @Test
    void disputeShareIsProportionalAndBounded() {
        assertEquals(10_000L, StripeMapper.disputeUsdCents(5000, 5000, 10_000));
        assertEquals(10_000L, StripeMapper.disputeUsdCents(9000, 5000, 10_000));
        assertEquals(5_000L, StripeMapper.disputeUsdCents(2500, 5000, 10_000));
        assertEquals(1L, StripeMapper.disputeUsdCents(1, 100_000, 10));
        assertEquals(10_000L, StripeMapper.disputeUsdCents(100, 0, 10_000));
    }

    @Test
    void evidenceGoesToItsFieldAndTheRestToUncategorised() {
        Map<String, String> f = StripeMapper.evidenceFields(List.of(
                Map.of("type", "PRODUCT_DESCRIPTION", "text", "A subscription"),
                Map.of("type", "SHIPPING_PROOF", "text", "Delivered", "attachments", List.of("https://x/y.pdf"))));
        assertEquals("A subscription", f.get("evidence[product_description]"));
        String other = f.get("evidence[uncategorized_text]");
        assertTrue(other.contains("SHIPPING_PROOF: Delivered"));
        assertTrue(other.contains("Attachment: https://x/y.pdf"));
    }

    @Test
    void evidenceIsTruncatedToStripesLimit() {
        Map<String, String> f = StripeMapper.evidenceFields(List.of(Map.of("type", "PRODUCT_DESCRIPTION", "text", "x".repeat(25_000))));
        assertEquals(19_000, f.get("evidence[product_description]").length());
    }
}
