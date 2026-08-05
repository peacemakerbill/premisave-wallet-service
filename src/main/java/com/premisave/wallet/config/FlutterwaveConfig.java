package com.premisave.wallet.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Flutterwave v3 API configuration. Unlike M-Pesa/PayPal, Flutterwave uses a
 * SINGLE base URL for both sandbox and live traffic — which mode you're in
 * is determined entirely by which kind of secret key you configure
 * (FLWSECK_TEST-... for sandbox vs FLWSECK-... for live), not by the host.
 * The "environment" field here is kept only for logging/consistency with
 * the other provider configs, not for URL selection.
 *
 * See https://developer.flutterwave.com/docs
 */
@Data
@Component
@ConfigurationProperties(prefix = "flutterwave")
public class FlutterwaveConfig {

    private String secretKey;
    private String publicKey;
    private String encryptionKey;

    /** "sandbox" or "production" — informational only, see class javadoc. */
    private String environment;

    /**
     * Arbitrary shared-secret string configured in the Flutterwave Dashboard
     * (Settings → Webhooks → Secret Hash). Flutterwave echoes this back
     * verbatim in every webhook request's "verif-hash" header — this is a
     * simple string-equality check, NOT an HMAC signature. See
     * FlutterwaveService.verifyWebhookSignature for the caveat this implies.
     */
    private String webhookSecretHash;

    /**
     * Where Flutterwave's hosted checkout page redirects the customer back
     * to after payment (success, failure, or cancellation) — the frontend
     * then reads the tx_ref/transaction_id query params it appends and
     * calls POST /wallet/deposit/flutterwave/confirm.
     */
    private String redirectUrl;

    private final Transfer transfer = new Transfer();

    public String baseUrl() {
        return "https://api.flutterwave.com/v3";
    }

    @Data
    public static class Transfer {
        /**
         * Our own webhook endpoint, passed to Flutterwave as callback_url
         * on each transfer request. Optional — Flutterwave also fires the
         * account-wide "transfer.completed" webhook regardless of whether
         * this is set, so this is a per-transfer convenience, not the only
         * way completion is reported.
         */
        private String callbackUrl;

        /** Confirm actual tier limits with Flutterwave for your account. */
        private BigDecimal minAmount = new BigDecimal("1");
        private BigDecimal maxAmount = new BigDecimal("5000");
    }
}