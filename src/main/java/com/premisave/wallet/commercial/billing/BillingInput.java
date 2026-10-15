package com.premisave.wallet.commercial.billing;

import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.util.RequestValidators;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;

/** Small input helpers shared by the billing services. */
public final class BillingInput {

    private BillingInput() {}

    /** Parses an ISO 8601 UTC time; null when the text is empty. */
    public static Instant instant(String field, String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw CommercialException.validation(field, "must be an ISO 8601 UTC time such as 2026-12-01T00:00:00Z");
        }
    }

    /** A subscription's metadata, with room left for the keys billing adds to every payment. */
    public static Map<String, String> subscriptionMetadata(Map<String, String> raw) {
        Map<String, String> m = RequestValidators.metadata(raw);
        if (m.size() > 16) throw CommercialException.validation("metadata", "allows at most 16 keys on a subscription");
        return m;
    }

    public static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
