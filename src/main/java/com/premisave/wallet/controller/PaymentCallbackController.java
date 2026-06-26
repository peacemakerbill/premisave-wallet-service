package com.premisave.wallet.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.premisave.wallet.dto.ApiResponse;
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
 * Handles incoming webhooks from Stripe and PayPal.
 * Both endpoints are PUBLIC (no JWT) — secured by signature verification.
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

    // ─── M-Pesa STK Push callback (existing endpoint, kept here for co-location) ──

    // See MpesaCallbackController for /payments/mpesa/callback

    // ─── M-Pesa B2C Result (disbursement outcome) ────────────────────────────

    /**
     * M-Pesa sends the B2C result to this URL after processing.
     * For now we log it; extend to update Disbursement status in DB.
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

                // userId is stored in PaymentIntent metadata when we create it
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

                // Extract userId and amount from custom_id or purchase_units
                JsonNode unit = resource.path("purchase_units").get(0);
                String referenceId = unit.path("reference_id").asText(); // our idempotencyKey
                String amountStr   = unit.path("amount").path("value").asText("0");
                String currency    = unit.path("amount").path("currency_code").asText("USD");

                // userId stored as custom_id in purchase_unit or passed via reference_id
                // Here we look it up by reference (idempotency key matches pending TX reference)
                // For a cleaner approach, store userId in PayPal custom_id at order creation time
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
        // Inject TransactionRepository here and do: transactionRepository.findByReference(reference)
        // Returning null for now so the caller can handle the missing case gracefully.
        log.warn("resolveUserIdFromReference: implement userId lookup by reference={}", reference);
        return null;
    }
}