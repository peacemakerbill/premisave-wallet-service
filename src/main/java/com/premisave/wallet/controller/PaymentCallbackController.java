package com.premisave.wallet.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.premisave.wallet.dto.ApiResponse;
import com.premisave.wallet.dto.MpesaCallbackRequest;
import com.premisave.wallet.service.DepositService;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.premisave.wallet.service.StripeService;

import java.math.BigDecimal;

/**
 * Handles incoming webhooks/callbacks from all payment providers:
 * M-Pesa (STK Push + B2C), Stripe, and PayPal.
 * All endpoints are PUBLIC (no JWT) — secured by signature verification
 * or IP allowlist at the gateway/firewall level.
 */
@Slf4j
@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentCallbackController {

    private final DepositService depositService;
    private final StripeService stripeService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${stripe.webhook-secret:}")
    private String stripeWebhookSecret;

    // ─── M-Pesa STK Push Callback ────────────────────────────────────────────

    /**
     * Receives M-Pesa STK Push callback from Safaricom Daraja.
     * Secured via IP allowlist at the gateway/firewall level (no JWT).
     */
    @PostMapping("/mpesa/callback")
    public ResponseEntity<ApiResponse<Void>> handleMpesaCallback(@RequestBody MpesaCallbackRequest callback) {
        log.info("M-Pesa STK callback received: transID={} amount={} msisdn={}",
                callback.getTransID(), callback.getTransAmount(), callback.getMSISDN());

        try {
            BigDecimal amount = new BigDecimal(callback.getTransAmount());
            String accountNumber = callback.getBillRefNumber(); // email used as account ref
            String description = "M-Pesa deposit from " + callback.getFirstName()
                    + " (" + callback.getMSISDN() + ")";

            depositService.creditWalletFromCallback(accountNumber, amount,
                    callback.getTransID(), description);

            return ResponseEntity.ok(ApiResponse.success("Callback processed"));
        } catch (Exception e) {
            log.error("Failed to process M-Pesa callback: transID={}", callback.getTransID(), e);
            // Always return 200 to Safaricom — they retry on non-200
            return ResponseEntity.ok(ApiResponse.error("Callback processing failed: " + e.getMessage()));
        }
    }

    // ─── M-Pesa B2C Result (disbursement outcome) ────────────────────────────

    /**
     * M-Pesa sends the B2C result to this URL after processing.
     */
    @PostMapping("/mpesa/b2c/result")
    public ResponseEntity<Void> mpesaB2cResult(@RequestBody String payload) {
        log.info("M-Pesa B2C result received: {}", payload);
        // TODO: parse result, update Disbursement.status in DB
        return ResponseEntity.ok().build();
    }

    @PostMapping("/mpesa/b2c/timeout")
    public ResponseEntity<Void> mpesaB2cTimeout(@RequestBody String payload) {
        log.warn("M-Pesa B2C timeout: {}", payload);
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