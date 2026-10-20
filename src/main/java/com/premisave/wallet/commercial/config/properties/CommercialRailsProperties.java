package com.premisave.wallet.commercial.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Locale;

/**
 * Settings for the live provider rails of the commercial API (Stripe, PayPal, Flutterwave, NOWPayments and
 * the Premisave wallet). Every rail is off unless its enabled flag is true. The rails reuse the credentials
 * already configured for the wallet service (stripe.*, paypal.*, flutterwave.*, nowpayments.*); only the
 * settings below are new. Out of range values fall back to the defaults.
 */
@ConfigurationProperties(prefix = "commercial.rails")
public record CommercialRailsProperties(Polling polling, Stripe stripe, Paypal paypal, Flutterwave flutterwave,
                                        NowPayments nowpayments, Premisave premisave, Integer httpTimeoutSeconds) {

    public CommercialRailsProperties {
        if (polling == null) polling = new Polling(null, null, null, null);
        if (stripe == null) stripe = new Stripe(null, null, null, null, null, null, null, null);
        if (paypal == null) paypal = new Paypal(null, null, null, null, null);
        if (flutterwave == null) flutterwave = new Flutterwave(null, null, null, null, null, null, null);
        if (nowpayments == null) nowpayments = new NowPayments(null, null, null, null);
        if (premisave == null) premisave = new Premisave(null, null, null, null, null, null);
        if (httpTimeoutSeconds == null || httpTimeoutSeconds < 3 || httpTimeoutSeconds > 60) httpTimeoutSeconds = 20;
    }

    /** How often open transactions are looked up with their provider. */
    public record Polling(Boolean enabled, Long intervalMs, Integer afterSeconds, Integer batchSize) {
        public Polling {
            if (enabled == null) enabled = Boolean.TRUE;
            if (intervalMs == null || intervalMs < 1000) intervalMs = 20_000L;
            if (afterSeconds == null || afterSeconds < 0 || afterSeconds > 3600) afterSeconds = 15;
            if (batchSize == null || batchSize < 1 || batchSize > 500) batchSize = 50;
        }
    }

    /**
     * publishableKey is returned to the customer's browser with a card payment. webhookSecret signs the
     * commercial webhook endpoint (its own Dashboard destination, not the wallet's).
     */
    public record Stripe(Boolean enabled, String publishableKey, String webhookSecret, List<String> currencies,
                         Long minUsdCents, Long maxUsdCents, Integer webhookToleranceSeconds, Integer setupTimeoutHours) {
        public Stripe {
            if (enabled == null) enabled = Boolean.FALSE;
            currencies = normalise(currencies, List.of("USD"));
            if (minUsdCents == null || minUsdCents < 1) minUsdCents = 50L;
            if (maxUsdCents == null || maxUsdCents < minUsdCents) maxUsdCents = 10_000_000L;
            if (webhookToleranceSeconds == null || webhookToleranceSeconds < 30 || webhookToleranceSeconds > 3600) webhookToleranceSeconds = 300;
            if (setupTimeoutHours == null || setupTimeoutHours < 1 || setupTimeoutHours > 720) setupTimeoutHours = 24;
        }

        public boolean webhookConfigured() { return webhookSecret != null && webhookSecret.length() >= 16; }
    }

    public record Paypal(Boolean enabled, List<String> currencies, Long minUsdCents, Long maxUsdCents, Integer setupTimeoutHours) {
        public Paypal {
            if (enabled == null) enabled = Boolean.FALSE;
            currencies = normalise(currencies, List.of("USD"));
            if (minUsdCents == null || minUsdCents < 1) minUsdCents = 100L;
            if (maxUsdCents == null || maxUsdCents < minUsdCents) maxUsdCents = 10_000_000L;
            if (setupTimeoutHours == null || setupTimeoutHours < 1 || setupTimeoutHours > 720) setupTimeoutHours = 24;
        }
    }

    /**
     * mobileMoneyCurrencies are the local currencies mobile money can be collected in. bankCurrencies are the
     * currencies bank payouts are sent in. sourceCurrency is the currency of the Flutterwave balance transfers
     * are paid from. customerEmailDomain builds the placeholder email Flutterwave requires for a customer who
     * gave only a phone number.
     */
    public record Flutterwave(Boolean enabled, List<String> mobileMoneyCurrencies, List<String> bankCurrencies,
                              String sourceCurrency, String customerEmailDomain, Long minUsdCents, Long maxUsdCents) {
        public Flutterwave {
            if (enabled == null) enabled = Boolean.FALSE;
            mobileMoneyCurrencies = normalise(mobileMoneyCurrencies, List.of("GHS", "UGX", "TZS", "RWF", "ZMW"));
            bankCurrencies = normalise(bankCurrencies, List.of("NGN", "GHS", "KES", "UGX", "TZS", "RWF", "ZAR"));
            if (sourceCurrency == null || sourceCurrency.trim().length() != 3) sourceCurrency = "USD";
            sourceCurrency = sourceCurrency.trim().toUpperCase(Locale.ROOT);
            if (customerEmailDomain == null || customerEmailDomain.isBlank()) customerEmailDomain = "payments.premisave.com";
            if (minUsdCents == null || minUsdCents < 1) minUsdCents = 50L;
            if (maxUsdCents == null || maxUsdCents < minUsdCents) maxUsdCents = 5_000_000L;
        }
    }

    public record NowPayments(Boolean enabled, String defaultPayCurrency, Long minUsdCents, Long maxUsdCents) {
        public NowPayments {
            if (enabled == null) enabled = Boolean.FALSE;
            if (defaultPayCurrency == null || defaultPayCurrency.isBlank()) defaultPayCurrency = "usdttrc20";
            defaultPayCurrency = defaultPayCurrency.trim().toLowerCase(Locale.ROOT);
            if (minUsdCents == null || minUsdCents < 1) minUsdCents = 500L;
            if (maxUsdCents == null || maxUsdCents < minUsdCents) maxUsdCents = 10_000_000L;
        }
    }

    /**
     * consentMinutes is how long a wallet owner has to approve a payment. mandateMaxUsdCents caps a single
     * charge made under a standing approval (a subscription).
     */
    public record Premisave(Boolean enabled, Integer consentMinutes, Long mandateMaxUsdCents, Long minUsdCents,
                            Long maxUsdCents, Integer mandateHours) {
        public Premisave {
            if (enabled == null) enabled = Boolean.FALSE;
            if (consentMinutes == null || consentMinutes < 1 || consentMinutes > 1440) consentMinutes = 15;
            if (mandateMaxUsdCents == null || mandateMaxUsdCents < 1) mandateMaxUsdCents = 50_000L;
            if (minUsdCents == null || minUsdCents < 1) minUsdCents = 1L;
            if (maxUsdCents == null || maxUsdCents < minUsdCents) maxUsdCents = 5_000_000L;
            if (mandateHours == null || mandateHours < 1 || mandateHours > 720) mandateHours = 48;
        }
    }

    static List<String> normalise(List<String> raw, List<String> fallback) {
        if (raw == null || raw.isEmpty()) return fallback;
        List<String> out = raw.stream().filter(s -> s != null && !s.isBlank())
                .map(s -> s.trim().toUpperCase(Locale.ROOT)).distinct().toList();
        return out.isEmpty() ? fallback : out;
    }
}
