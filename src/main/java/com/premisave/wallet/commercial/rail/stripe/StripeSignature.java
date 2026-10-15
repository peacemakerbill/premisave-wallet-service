package com.premisave.wallet.commercial.rail.stripe;

import com.premisave.wallet.commercial.util.Hashing;

/**
 * Checks the Stripe-Signature header of a webhook: "t=timestamp,v1=signature[,v1=...]", where the signature is
 * HMAC-SHA256 of "timestamp.payload" with the endpoint's signing secret. A timestamp too far from now is refused,
 * so a captured webhook cannot be replayed later.
 */
public final class StripeSignature {

    private StripeSignature() {}

    public static boolean verify(String secret, String header, String payload, long nowEpochSeconds, long toleranceSeconds) {
        if (secret == null || secret.isBlank() || header == null || header.isBlank() || payload == null) return false;
        Long timestamp = null;
        java.util.List<String> signatures = new java.util.ArrayList<>();
        for (String part : header.split(",")) {
            int eq = part.indexOf('=');
            if (eq < 1) continue;
            String key = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            if (key.equals("t")) {
                try {
                    timestamp = Long.parseLong(value);
                } catch (NumberFormatException e) {
                    return false;
                }
            } else if (key.equals("v1")) {
                signatures.add(value);
            }
        }
        if (timestamp == null || signatures.isEmpty()) return false;
        if (Math.abs(nowEpochSeconds - timestamp) > toleranceSeconds) return false;
        String expected = Hashing.hmacSha256Hex(secret, timestamp + "." + payload);
        for (String s : signatures) {
            if (Hashing.constantTimeEquals(expected, s)) return true;
        }
        return false;
    }
}
