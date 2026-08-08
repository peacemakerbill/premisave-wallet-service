package com.premisave.wallet.config;

import com.stripe.StripeClient;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "stripe")
public class StripeConfig {

    // sk_test_/sk_live_ (Standard) or rk_test_/rk_live_ (Restricted).
    // No separate sandbox/prod base URL like Flutterwave/M-Pesa — Stripe
    // always hits api.stripe.com; test vs live is decided by this key alone.
    // If using a restricted key, make sure it has permissions granted in the
    // Dashboard — new restricted keys default to Access policy "None" and
    // will 403 on every call until scoped (Customers, PaymentIntents,
    // PaymentMethods, Refunds, Payouts as needed).
    private String secretKey;

    // whsec_... from Dashboard -> Developers -> Webhooks -> your endpoint ->
    // Signing secret. Used to verify the Stripe-Signature header.
    private String webhookSecret;

    @Bean
    public StripeClient stripeClient() {
        return new StripeClient(secretKey);
    }
}