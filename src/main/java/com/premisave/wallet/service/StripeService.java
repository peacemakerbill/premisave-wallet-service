package com.premisave.wallet.service;

import com.stripe.Stripe;
import com.stripe.exception.CardException;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentMethod;
import com.stripe.model.Payout;
import com.stripe.model.SetupIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PayoutCreateParams;
import com.stripe.param.SetupIntentCreateParams;
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

    // ─── Customer ──────────────────────────────────────────────────────────

    /**
     * Creates a Stripe Customer for this user — a stable anchor that lets a
     * saved card be reused for future off-session deposits. Note: test-mode
     * and live-mode customers are entirely separate objects, so a
     * stripeCustomerId saved while testing in sandbox will not exist if you
     * flip to live keys (nor vice versa).
     */
    public String createCustomer(String email, String userId) {
        try {
            CustomerCreateParams params = CustomerCreateParams.builder()
                    .setEmail(email)
                    .putMetadata("user_id", userId)
                    .build();
            Customer customer = Customer.create(params);
            log.info("Stripe Customer created: id={} userId={}", customer.getId(), userId);
            return customer.getId();
        } catch (StripeException e) {
            throw new RuntimeException("Failed to create Stripe customer: " + e.getMessage(), e);
        }
    }

    // ─── SetupIntent (save a card without charging it) ──────────────────────

    /**
     * Starts a SetupIntent so the frontend can collect and attach a card to
     * the given Customer via Stripe.js/Elements — raw card data goes
     * straight from the browser to Stripe and never touches our servers.
     * See DepositService.createStripeSetupIntent / confirmStripeSetupIntent.
     */
    public SetupIntent createSetupIntent(String customerId) {
        try {
            SetupIntentCreateParams params = SetupIntentCreateParams.builder()
                    .setCustomer(customerId)
                    .addPaymentMethodType("card")
                    .setUsage(SetupIntentCreateParams.Usage.OFF_SESSION)
                    .build();
            SetupIntent intent = SetupIntent.create(params);
            log.info("Stripe SetupIntent created: id={} customerId={}", intent.getId(), customerId);
            return intent;
        } catch (StripeException e) {
            throw new RuntimeException("Failed to create Stripe SetupIntent: " + e.getMessage(), e);
        }
    }

    public SetupIntent retrieveSetupIntent(String setupIntentId) {
        try {
            return SetupIntent.retrieve(setupIntentId);
        } catch (StripeException e) {
            throw new RuntimeException("Failed to retrieve Stripe SetupIntent " + setupIntentId + ": " + e.getMessage(), e);
        }
    }

    public PaymentMethod retrievePaymentMethod(String paymentMethodId) {
        try {
            return PaymentMethod.retrieve(paymentMethodId);
        } catch (StripeException e) {
            throw new RuntimeException("Failed to retrieve Stripe PaymentMethod " + paymentMethodId + ": " + e.getMessage(), e);
        }
    }

    // ─── PaymentIntent (deposits) ────────────────────────────────────────────

    /**
     * Result of a deposit attempt. When requiresAction is true, clientSecret
     * must be confirmed client-side with Stripe.js before the deposit
     * completes (either a brand-new card, or a saved card that needed fresh
     * 3DS authentication this time).
     */
    public record StripePaymentIntentResult(
            String paymentIntentId, String clientSecret, String status,
            String customerId, String paymentMethodId, boolean requiresAction) {}

    /**
     * Creates (and, if a saved payment method is supplied, immediately
     * attempts to confirm) a PaymentIntent for a wallet deposit.
     *
     * - existingPaymentMethodId == null: first-time deposit. Returns a
     *   client_secret for the frontend to collect a card via Stripe.js.
     *   setup_future_usage(OFF_SESSION) is set so, once this succeeds, the
     *   card is saved on the Customer for one-click reloads next time.
     * - existingPaymentMethodId != null: attempts an off-session charge on
     *   the saved card immediately (confirm=true) — no frontend interaction
     *   needed at all if it succeeds. If the card issuer demands fresh
     *   authentication (CardException with code "authentication_required"),
     *   falls back to a client-confirmed PaymentIntent pre-filled with the
     *   same saved card, rather than failing the deposit outright.
     */
    public StripePaymentIntentResult createOrChargePaymentIntent(
            String customerId, String existingPaymentMethodId, BigDecimal amountKes,
            String currency, String idempotencyKey, String userId) {

        long amountCents = amountKes.multiply(BigDecimal.valueOf(100)).longValue();
        RequestOptions options = RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();

        PaymentIntentCreateParams.Builder builder = PaymentIntentCreateParams.builder()
                .setAmount(amountCents)
                .setCurrency(currency.toLowerCase())
                .setDescription("Premisave wallet deposit")
                .setCustomer(customerId)
                .putMetadata("idempotency_key", idempotencyKey)
                .putMetadata("user_id", userId);

        try {
            if (existingPaymentMethodId != null) {
                builder.setPaymentMethod(existingPaymentMethodId)
                        .setOffSession(true)
                        .setConfirm(true);
                PaymentIntent intent = PaymentIntent.create(builder.build(), options);
                log.info("Stripe off-session charge succeeded: id={} status={}", intent.getId(), intent.getStatus());
                return new StripePaymentIntentResult(intent.getId(), intent.getClientSecret(), intent.getStatus(),
                        intent.getCustomer(), intent.getPaymentMethod(), false);
            } else {
                builder.setSetupFutureUsage(PaymentIntentCreateParams.SetupFutureUsage.OFF_SESSION);
                PaymentIntent intent = PaymentIntent.create(builder.build(), options);
                log.info("Stripe PaymentIntent created (new card): id={} status={}", intent.getId(), intent.getStatus());
                return new StripePaymentIntentResult(intent.getId(), intent.getClientSecret(), intent.getStatus(),
                        intent.getCustomer(), intent.getPaymentMethod(), false);
            }
        } catch (CardException e) {
            if (existingPaymentMethodId != null && "authentication_required".equals(e.getCode())) {
                log.warn("Saved card requires fresh authentication for userId={} — falling back to client-confirmed flow", userId);
                try {
                    PaymentIntentCreateParams retryParams = PaymentIntentCreateParams.builder()
                            .setAmount(amountCents)
                            .setCurrency(currency.toLowerCase())
                            .setDescription("Premisave wallet deposit")
                            .setCustomer(customerId)
                            .setPaymentMethod(existingPaymentMethodId)
                            .putMetadata("idempotency_key", idempotencyKey)
                            .putMetadata("user_id", userId)
                            .build();
                    RequestOptions retryOptions = RequestOptions.builder()
                            .setIdempotencyKey(idempotencyKey + "-3ds").build();
                    PaymentIntent retryIntent = PaymentIntent.create(retryParams, retryOptions);
                    return new StripePaymentIntentResult(retryIntent.getId(), retryIntent.getClientSecret(),
                            retryIntent.getStatus(), retryIntent.getCustomer(), retryIntent.getPaymentMethod(), true);
                } catch (StripeException retryEx) {
                    throw new RuntimeException("Stripe deposit failed on re-authentication retry: " + retryEx.getMessage(), retryEx);
                }
            }
            throw new RuntimeException("Stripe deposit failed: " + e.getMessage(), e);
        } catch (StripeException e) {
            throw new RuntimeException("Stripe deposit failed: " + e.getMessage(), e);
        }
    }

    public PaymentIntent retrievePaymentIntent(String paymentIntentId) {
        try {
            return PaymentIntent.retrieve(paymentIntentId);
        } catch (StripeException e) {
            throw new RuntimeException("Failed to retrieve Stripe PaymentIntent " + paymentIntentId + ": " + e.getMessage(), e);
        }
    }

    // ─── Payout (Premisave's own bank account — NOT per-user; see chat note) ─

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

    public com.stripe.model.Event constructWebhookEvent(String payload, String sigHeader, String webhookSecret) {
        try {
            return com.stripe.net.Webhook.constructEvent(payload, sigHeader, webhookSecret);
        } catch (Exception e) {
            throw new RuntimeException("Invalid Stripe webhook signature", e);
        }
    }
}