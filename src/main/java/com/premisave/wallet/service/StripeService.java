package com.premisave.wallet.service;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Payout;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PayoutCreateParams;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Slf4j
@Service
public class StripeService {

    @Value("${stripe.secret-key}")
    private String secretKey;

    @PostConstruct
    public void init() {
        Stripe.apiKey = secretKey;
    }

    /**
     * Creates a Stripe PaymentIntent for wallet deposits.
     * Returns the client_secret the frontend uses to confirm with Stripe.js.
     *
     * Metadata carries BOTH idempotency_key (used to match this PaymentIntent
     * back to the pending Transaction created at initiation — see
     * DepositService.creditWalletFromStripeCallback) and user_id (defensive
     * cross-check / logging). The webhook's own reconciliation relies on
     * idempotency_key, not user_id.
     *
     * @param amountKes  amount in KES (or your currency)
     * @param currency   ISO 4217 lowercase, e.g. "kes"
     * @param idempotencyKey  your reference / idempotency key
     * @param userId     the depositing user's id, stored as metadata
     * @return client_secret of the PaymentIntent
     */
    public String createPaymentIntent(BigDecimal amountKes, String currency, String idempotencyKey, String userId) {
        // Stripe amounts are in smallest currency unit (cents / fils).
        // KES uses cents (1 KES = 100 cents).
        long amountCents = amountKes.multiply(BigDecimal.valueOf(100)).longValue();

        try {
            PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                    .setAmount(amountCents)
                    .setCurrency(currency.toLowerCase())
                    .setDescription("Premisave wallet deposit")
                    .putMetadata("idempotency_key", idempotencyKey)
                    .putMetadata("user_id", userId)
                    .build();

            com.stripe.net.RequestOptions options = com.stripe.net.RequestOptions.builder()
                    .setIdempotencyKey(idempotencyKey)
                    .build();

            PaymentIntent intent = PaymentIntent.create(params, options);
            log.info("Stripe PaymentIntent created: id={} status={}", intent.getId(), intent.getStatus());
            return intent.getClientSecret();
        } catch (StripeException e) {
            log.error("Stripe PaymentIntent creation failed", e);
            throw new RuntimeException("Stripe deposit failed: " + e.getMessage(), e);
        }
    }

    /**
     * Retrieves the current state of a PaymentIntent directly from Stripe —
     * used by the frontend-triggered confirm endpoint (see
     * DepositService.confirmStripeDeposit) as a synchronous alternative/backstop
     * to the webhook. Especially useful in sandbox: without `stripe listen
     * --forward-to` (or ngrok) running, the webhook never fires locally, but
     * this call works immediately since it's a direct API request, not a
     * callback.
     */
    public PaymentIntent retrievePaymentIntent(String paymentIntentId) {
        try {
            return PaymentIntent.retrieve(paymentIntentId);
        } catch (StripeException e) {
            throw new RuntimeException("Failed to retrieve Stripe PaymentIntent " + paymentIntentId + ": " + e.getMessage(), e);
        }
    }

    /**
     * Creates a Stripe Payout to the connected bank account / debit card.
     * Requires the Stripe account to have an external bank account configured.
     *
     * @param amountKes  amount in KES
     * @param currency   ISO 4217 lowercase
     * @return Stripe Payout ID
     */
    public String processPayout(BigDecimal amountKes, String currency) {
        long amountCents = amountKes.multiply(BigDecimal.valueOf(100)).longValue();

        try {
            PayoutCreateParams params = PayoutCreateParams.builder()
                    .setAmount(amountCents)
                    .setCurrency(currency.toLowerCase())
                    .setDescription("Premisave wallet disbursement")
                    .build();

            Payout payout = Payout.create(params);
            log.info("Stripe Payout created: id={} status={}", payout.getId(), payout.getStatus());
            return payout.getId();
        } catch (StripeException e) {
            log.error("Stripe Payout failed", e);
            throw new RuntimeException("Stripe payout failed: " + e.getMessage(), e);
        }
    }

    /**
     * Verifies a Stripe webhook signature and returns the event type.
     * Call this from your webhook controller before processing the event.
     */
    public com.stripe.model.Event constructWebhookEvent(String payload, String sigHeader, String webhookSecret) {
        try {
            return com.stripe.net.Webhook.constructEvent(payload, sigHeader, webhookSecret);
        } catch (Exception e) {
            throw new RuntimeException("Invalid Stripe webhook signature", e);
        }
    }
}