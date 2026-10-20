package com.premisave.wallet.commercial.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the live M-Pesa rail of the commercial API. The rail is off
 * unless commercial.mpesa.enabled is true. It reuses the Daraja credentials
 * already configured under mpesa.daraja.*; only the commercial callback
 * address and its secret are new.
 *
 * callbackBaseUrl: public HTTPS address Safaricom can reach, with no trailing
 * path (for example https://wallet.example.com). The rail appends its own
 * callback paths under /payments/commercial/rail/.
 *
 * callbackSecret: signs the callback URLs so only Safaricom's genuine
 * callbacks for a transaction are accepted. At least 16 characters.
 */
@ConfigurationProperties(prefix = "commercial.mpesa")
public record CommercialMpesaProperties(
        Boolean enabled,
        String callbackBaseUrl,
        String callbackSecret,
        Integer collectionMaxKes,
        Integer pollAfterSeconds,
        Integer httpTimeoutSeconds) {

    public CommercialMpesaProperties {
        if (enabled == null) enabled = Boolean.FALSE;
        if (collectionMaxKes == null) collectionMaxKes = 250_000;
        if (pollAfterSeconds == null) pollAfterSeconds = 60;
        if (httpTimeoutSeconds == null) httpTimeoutSeconds = 15;
    }

    /** What is wrong with the settings, or null when the rail can start. */
    public String problem() {
        if (callbackBaseUrl == null || !callbackBaseUrl.trim().toLowerCase().startsWith("https://")) {
            return "commercial.mpesa.callback-base-url must be a public https address";
        }
        if (callbackSecret == null || callbackSecret.length() < 16) {
            return "commercial.mpesa.callback-secret must be at least 16 characters";
        }
        return null;
    }
}
