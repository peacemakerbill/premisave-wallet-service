package com.premisave.wallet.commercial.security;

import com.premisave.wallet.commercial.util.Hashing;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Verifies the HMAC on a request from the developer service.
 *
 * signingString = timestamp + "\n" + METHOD + "\n" + pathWithQuery + "\n" + hex(sha256(body))
 * signature     = hex(hmacSha256(secret, signingString))
 *
 * Any one of the configured secrets may match, which allows two to be live
 * during a rotation.
 */
public final class RequestSignatureVerifier {

    private RequestSignatureVerifier() {}

    public static String signingString(String timestamp, String method, String pathWithQuery, byte[] body) {
        return timestamp + "\n" + method.toUpperCase() + "\n" + pathWithQuery + "\n"
                + Hashing.sha256Hex(body == null ? new byte[0] : body);
    }

    public static String sign(String secret, String timestamp, String method, String pathWithQuery, byte[] body) {
        return Hashing.hmacSha256Hex(secret, signingString(timestamp, method, pathWithQuery, body));
    }

    /** Returns the reason the request must be rejected, or empty when the signature is valid. */
    public static Optional<String> verify(List<String> secrets, String method, String pathWithQuery, byte[] body,
                                          String timestampHeader, String signatureHeader,
                                          long nowEpochSeconds, int replayWindowSeconds) {
        if (timestampHeader == null || timestampHeader.isBlank() || signatureHeader == null || signatureHeader.isBlank()) {
            return Optional.of("X-Timestamp and X-Signature are required");
        }
        long ts;
        try {
            ts = Long.parseLong(timestampHeader.trim());
        } catch (NumberFormatException e) {
            return Optional.of("X-Timestamp must be unix time in seconds");
        }
        if (Math.abs(nowEpochSeconds - ts) > replayWindowSeconds) {
            return Optional.of("Request timestamp is outside the allowed window");
        }
        if (secrets == null || secrets.isEmpty()) {
            return Optional.of("Request signing is enabled but no signing secret is configured");
        }
        String provided = signatureHeader.trim().toLowerCase();
        if (provided.startsWith("sha256=")) provided = provided.substring("sha256=".length());
        String signingString = signingString(timestampHeader.trim(), method, pathWithQuery, body);
        boolean ok = false;
        for (String secret : secrets) {
            // Evaluate every secret so timing does not reveal which one matched.
            ok |= Hashing.constantTimeEquals(Hashing.hmacSha256Hex(secret, signingString), provided);
        }
        return ok ? Optional.empty() : Optional.of("Signature does not match");
    }

    static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }
}
