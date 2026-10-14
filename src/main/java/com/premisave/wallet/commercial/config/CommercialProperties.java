package com.premisave.wallet.commercial.config;

import com.premisave.wallet.commercial.enums.Environment;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * Settings for the commercial (payment service provider) API. Everything has
 * a safe default so the service starts without any commercial.* entries.
 *
 * serves: which environments this deployment accepts: live, sandbox or both.
 * Production should run one deployment per environment (live or sandbox) with
 * its own database and provider credentials; both is for local development.
 */
@ConfigurationProperties(prefix = "commercial")
public record CommercialProperties(
        Boolean enabled,
        String serves,
        Signing signing,
        Events events,
        Sandbox sandbox,
        Registrations registrations,
        Fees fees,
        Integer quoteTtlSeconds,
        List<String> currencies) {

    public CommercialProperties {
        if (enabled == null) enabled = Boolean.TRUE;
        if (serves == null || serves.isBlank()) serves = "both";
        if (signing == null) signing = new Signing(null, null, null);
        if (events == null) events = new Events(null, null, null, null, null, null, null);
        if (sandbox == null) sandbox = new Sandbox(null, null);
        if (registrations == null) registrations = new Registrations(null);
        if (fees == null) fees = new Fees(null, null);
        if (quoteTtlSeconds == null) quoteTtlSeconds = 900;
        currencies = currencies == null || currencies.isEmpty() ? List.of("USD", "KES") : List.copyOf(currencies);
    }

    public boolean supportsCurrency(String currency) {
        return currency != null && currencies.contains(currency.toUpperCase());
    }

    public boolean serves(Environment env) {
        String s = serves.trim().toLowerCase();
        return s.equals("both") || s.equals(env.pathSegment());
    }

    /** HMAC request signing between the developer service and this service. */
    public record Signing(Boolean enabled, List<String> secrets, Integer replayWindowSeconds) {
        public Signing {
            if (enabled == null) enabled = Boolean.FALSE;
            secrets = secrets == null ? List.of() : secrets.stream().filter(s -> s != null && !s.isBlank()).toList();
            if (replayWindowSeconds == null) replayWindowSeconds = 300;
        }
    }

    /** Forwarding of events to the developer service, which delivers them to merchant URLs. */
    public record Events(String developerServiceUrl, String apiKey, String signingSecret, Integer maxAttempts,
                         Integer dispatchBatchSize, Integer httpTimeoutSeconds, Boolean dispatcherEnabled) {
        public Events {
            if (maxAttempts == null) maxAttempts = 12;
            if (dispatchBatchSize == null) dispatchBatchSize = 50;
            if (httpTimeoutSeconds == null) httpTimeoutSeconds = 10;
            if (dispatcherEnabled == null) dispatcherEnabled = Boolean.TRUE;
        }

        public boolean configured() {
            return developerServiceUrl != null && !developerServiceUrl.isBlank();
        }
    }

    /** Behaviour of the simulated rail used for every sandbox payment and payout. */
    public record Sandbox(Integer autoResolveDelayMs, String redirectBase) {
        public Sandbox {
            if (autoResolveDelayMs == null) autoResolveDelayMs = 3000;
            if (redirectBase == null || redirectBase.isBlank()) redirectBase = "https://sandbox.invalid";
        }
    }

    public record Registrations(Integer maxPerApp) {
        public Registrations {
            if (maxPerApp == null) maxPerApp = 10;
        }
    }

    /** Fee rules by method name (MOBILE_MONEY, CARD ...). A method without a rule uses the default rule. */
    public record Fees(Map<String, FeeRule> collection, Map<String, FeeRule> payout) {
        public Fees {
            collection = collection == null ? Map.of() : collection;
            payout = payout == null ? Map.of() : payout;
        }
    }

    /** percentBps: basis points of the amount (100 = 1%). Fee = percent + flat, then clamped to min and max when set. */
    public record FeeRule(Integer percentBps, Long flatCents, Long minCents, Long maxCents) {
        public FeeRule {
            if (percentBps == null) percentBps = 0;
            if (flatCents == null) flatCents = 0L;
        }
    }
}
