package com.premisave.wallet.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.premisave.wallet.dto.ApiResponse;
import com.premisave.wallet.dto.MpesaResultCallbackRequest;
import com.premisave.wallet.dto.MpesaStkCallbackRequest;
import com.premisave.wallet.service.DepositService;
import com.premisave.wallet.service.DisbursementService;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.premisave.wallet.service.StripeService;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Handles incoming webhooks/callbacks from all payment providers:
 * M-Pesa (STK Push, B2C, B2B), Stripe, and PayPal.
 * All endpoints are PUBLIC (no JWT) — secured by signature verification
 * or IP allowlist at the gateway/firewall level.
 */
@Slf4j
@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentCallbackController {

    private final DepositService depositService;
    private final DisbursementService disbursementService;
    private final StripeService stripeService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${stripe.webhook-secret:}")
    private String stripeWebhookSecret;

    // ─── M-Pesa STK Push Callback ────────────────────────────────────────────

    /**
     * Receives M-Pesa STK Push callback from Safaricom Daraja.
     * Secured via IP allowlist at the gateway/firewall level (no JWT).
     *
     * Safaricom's payload is nested under Body.stkCallback — see
     * MpesaStkCallbackRequest for the exact shape. On success (ResultCode == 0)
     * the paid amount, receipt number, and phone are inside CallbackMetadata.Item;
     * there is no account number or email in this payload, so the transaction is
     * matched back to a wallet via CheckoutRequestID (see DepositService).
     */
    @PostMapping("/mpesa/callback")
    public ResponseEntity<ApiResponse<Void>> handleMpesaCallback(@RequestBody MpesaStkCallbackRequest callback) {
        MpesaStkCallbackRequest.StkCallback stk = callback.getBody().getStkCallback();
        String checkoutRequestId = stk.getCheckoutRequestID();

        log.info("M-Pesa STK callback received: checkoutRequestId={} resultCode={} resultDesc={}",
                checkoutRequestId, stk.getResultCode(), stk.getResultDesc());

        try {
            if (stk.getResultCode() != 0) {
                // Not an error on our side — user cancelled, wrong PIN, timed out, etc.
                depositService.markStkTransactionFailed(checkoutRequestId, stk.getResultDesc());
                return ResponseEntity.ok(ApiResponse.success("Callback processed (payment not completed)"));
            }

            Map<String, Object> values = new HashMap<>();
            if (stk.getCallbackMetadata() != null && stk.getCallbackMetadata().getItem() != null) {
                for (MpesaStkCallbackRequest.CallbackItem item : stk.getCallbackMetadata().getItem()) {
                    values.put(item.getName(), item.getValue());
                }
            }

            BigDecimal amount = new BigDecimal(String.valueOf(values.get("Amount")));
            String mpesaReceipt = String.valueOf(values.get("MpesaReceiptNumber"));
            String phoneNumber = String.valueOf(values.get("PhoneNumber"));

            depositService.creditWalletFromStkCallback(checkoutRequestId, amount, mpesaReceipt, phoneNumber);

            return ResponseEntity.ok(ApiResponse.success("Callback processed"));
        } catch (Exception e) {
            log.error("Failed to process M-Pesa STK callback: checkoutRequestId={}", checkoutRequestId, e);
            // Always return 200 to Safaricom — they retry on non-200
            return ResponseEntity.ok(ApiResponse.error("Callback processing failed: " + e.getMessage()));
        }
    }

    // ─── M-Pesa B2C Result (disbursement outcome) ────────────────────────────

    /**
     * Safaricom sends the real B2C outcome here — the initial paymentrequest
     * acceptance is NOT the final result. ResultCode == 0 means the payout
     * actually succeeded; anything else means it failed and must be refunded.
     * See DisbursementService.completeMpesaDisbursement().
     */
    @PostMapping("/mpesa/b2c/result")
    public ResponseEntity<Void> mpesaB2cResult(@RequestBody MpesaResultCallbackRequest callback) {
        var result = callback.getResult();
        log.info("M-Pesa B2C result: conversationId={} resultCode={} resultDesc={}",
                result.getConversationID(), result.getResultCode(), result.getResultDesc());

        try {
            boolean success = result.getResultCode() == 0;
            disbursementService.completeMpesaDisbursement(
                    result.getConversationID(), success, result.getResultDesc(), result.getTransactionID());
        } catch (Exception e) {
            log.error("Failed to process M-Pesa B2C result: conversationId={}", result.getConversationID(), e);
        }

        // Always 200 — Safaricom does not expect a meaningful body here.
        return ResponseEntity.ok().build();
    }

    /**
     * Fired when Safaricom couldn't reach the ResultURL in time. The
     * disbursement stays PENDING — money remains held pending manual
     * reconciliation or a later result (see DisbursementService's sweeper).
     */
    @PostMapping("/mpesa/b2c/timeout")
    public ResponseEntity<Void> mpesaB2cTimeout(@RequestBody MpesaResultCallbackRequest callback) {
        String conversationId = callback.getResult() != null ? callback.getResult().getConversationID() : null;
        log.warn("M-Pesa B2C timeout: conversationId={}", conversationId);
        try {
            disbursementService.markMpesaDisbursementTimedOut(conversationId);
        } catch (Exception e) {
            log.error("Failed to process M-Pesa B2C timeout: conversationId={}", conversationId, e);
        }
        return ResponseEntity.ok().build();
    }

    // ─── M-Pesa B2B Result (business-to-business payment outcome) ───────────

    @PostMapping("/mpesa/b2b/result")
    public ResponseEntity<Void> mpesaB2bResult(@RequestBody MpesaResultCallbackRequest callback) {
        var result = callback.getResult();
        log.info("M-Pesa B2B result: conversationId={} resultCode={} resultDesc={}",
                result.getConversationID(), result.getResultCode(), result.getResultDesc());

        try {
            boolean success = result.getResultCode() == 0;
            disbursementService.completeMpesaDisbursement(
                    result.getConversationID(), success, result.getResultDesc(), result.getTransactionID());
        } catch (Exception e) {
            log.error("Failed to process M-Pesa B2B result: conversationId={}", result.getConversationID(), e);
        }

        return ResponseEntity.ok().build();
    }

    @PostMapping("/mpesa/b2b/timeout")
    public ResponseEntity<Void> mpesaB2bTimeout(@RequestBody MpesaResultCallbackRequest callback) {
        String conversationId = callback.getResult() != null ? callback.getResult().getConversationID() : null;
        log.warn("M-Pesa B2B timeout: conversationId={}", conversationId);
        try {
            disbursementService.markMpesaDisbursementTimedOut(conversationId);
        } catch (Exception e) {
            log.error("Failed to process M-Pesa B2B timeout: conversationId={}", conversationId, e);
        }
        return ResponseEntity.ok().build();
    }

    // ─── Stripe Webhook ──────────────────────────────────────────────────────

    /**
     * Stripe sends events here. Secured by Stripe-Signature header verification.
     * Key event: payment_intent.succeeded → credit the wallet.
     */
    @PostMapping("/stripe/webhook")
    public ResponseEntity<ApiResponse<Void>> stripeWebhook(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String sigHeader) {

        try {
            Event event = stripeService.constructWebhookEvent(payload, sigHeader, stripeWebhookSecret);
            log.info("Stripe webhook received: type={} id={}", event.getType(), event.getId());

            if ("payment_intent.succeeded".equals(event.getType())) {
                PaymentIntent pi = (PaymentIntent) event.getDataObjectDeserializer()
                        .getObject().orElseThrow();

                String userId = pi.getMetadata().get("user_id");
                BigDecimal amount = BigDecimal.valueOf(pi.getAmount())
                        .divide(BigDecimal.valueOf(100)); // cents → major unit
                String currency = pi.getCurrency();

                if (userId != null) {
                    depositService.creditWalletFromStripe(userId, amount, pi.getId(), currency);
                    log.info("Stripe deposit completed: userId={} amount={}", userId, amount);
                } else {
                    log.warn("Stripe PaymentIntent {} has no user_id metadata — skipping credit", pi.getId());
                }
            }

            return ResponseEntity.ok(ApiResponse.success("Webhook processed"));
        } catch (Exception e) {
            log.error("Stripe webhook processing failed", e);
            return ResponseEntity.badRequest().body(ApiResponse.error("Webhook error: " + e.getMessage()));
        }
    }

    // ─── PayPal Webhook ──────────────────────────────────────────────────────

    /**
     * PayPal sends events here after order approval.
     * Key event: CHECKOUT.ORDER.APPROVED → capture + credit wallet.
     *
     * PayPal webhook verification requires calling the PayPal Verify API —
     * for brevity we trust the event body here; add verification in production.
     */
    @PostMapping("/paypal/webhook")
    public ResponseEntity<ApiResponse<Void>> paypalWebhook(@RequestBody String payload) {
        try {
            JsonNode event = objectMapper.readTree(payload);
            String eventType = event.path("event_type").asText();
            log.info("PayPal webhook received: type={}", eventType);

            if ("CHECKOUT.ORDER.APPROVED".equals(eventType)) {
                JsonNode resource = event.path("resource");
                String orderId = resource.path("id").asText();

                JsonNode unit = resource.path("purchase_units").get(0);
                String referenceId = unit.path("reference_id").asText();
                String amountStr   = unit.path("amount").path("value").asText("0");
                String currency    = unit.path("amount").path("currency_code").asText("USD");

                String userId = resolveUserIdFromReference(referenceId);

                if (userId != null) {
                    depositService.creditWalletFromPaypal(userId, new BigDecimal(amountStr), orderId, currency);
                } else {
                    log.warn("PayPal webhook: cannot resolve userId for orderId={}", orderId);
                }
            }

            return ResponseEntity.ok(ApiResponse.success("PayPal webhook processed"));
        } catch (Exception e) {
            log.error("PayPal webhook processing failed", e);
            return ResponseEntity.ok(ApiResponse.error("PayPal webhook error: " + e.getMessage()));
        }
    }

    /**
     * Looks up the userId from a pending transaction's reference field.
     * Replace with a proper lookup against TransactionRepository if you inject it here,
     * or store userId directly in PayPal's custom_id at order creation time (recommended).
     */
    private String resolveUserIdFromReference(String reference) {
        log.warn("resolveUserIdFromReference: implement userId lookup by reference={}", reference);
        return null;
    }
}