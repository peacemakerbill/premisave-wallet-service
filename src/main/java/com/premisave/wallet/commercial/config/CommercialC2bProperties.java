package com.premisave.wallet.commercial.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Settings for taking payments to an M-Pesa paybill by account reference (commercial.c2b.*). Off unless
 * enabled is true, and it also needs the commercial M-Pesa rail (commercial.mpesa.*) because it uses its
 * signed callback addresses and Daraja credentials.
 *
 * shortcode: the paybill customers pay. Safaricom allows one validation and confirmation address pair per
 * shortcode, so use a paybill that is not already registered to the wallet service's own C2B deposits, or
 * route both through CommercialC2bRouter.
 *
 * responseType: what Safaricom does with a payment when the validation address cannot be reached, Completed
 * (accept it) or Cancelled (refuse it). Cancelled is the default because a refused payment moves no money.
 *
 * merchantValidation: ask the merchant's registered VALIDATION callback before accepting a payment.
 * validationDeadlineMs: how long the merchant has to answer. Safaricom itself waits only a few seconds.
 */
@ConfigurationProperties(prefix = "commercial.c2b")
public record CommercialC2bProperties(
        Boolean enabled,
        String shortcode,
        String responseType,
        Integer validationDeadlineMs,
        Boolean merchantValidation,
        Boolean registerOnStartup,
        Integer reprocessAfterSeconds) {

    private static final Pattern SHORTCODE = Pattern.compile("\\d{5,7}");

    public CommercialC2bProperties {
        if (enabled == null) enabled = Boolean.FALSE;
        shortcode = shortcode == null ? null : shortcode.trim();
        responseType = "completed".equals(responseType == null ? "" : responseType.trim().toLowerCase(Locale.ROOT))
                ? "Completed" : "Cancelled";
        if (validationDeadlineMs == null || validationDeadlineMs < 500 || validationDeadlineMs > 10_000) validationDeadlineMs = 3000;
        if (merchantValidation == null) merchantValidation = Boolean.TRUE;
        if (registerOnStartup == null) registerOnStartup = Boolean.FALSE;
        if (reprocessAfterSeconds == null || reprocessAfterSeconds < 10 || reprocessAfterSeconds > 3600) reprocessAfterSeconds = 60;
    }

    /** What is wrong with the settings, or null when paybill payments can start. */
    public String problem() {
        if (shortcode == null || !SHORTCODE.matcher(shortcode).matches()) {
            return "commercial.c2b.shortcode must be the 5 to 7 digit paybill number";
        }
        return null;
    }
}
