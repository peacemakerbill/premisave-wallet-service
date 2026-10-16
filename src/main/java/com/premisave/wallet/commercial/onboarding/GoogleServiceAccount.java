package com.premisave.wallet.commercial.onboarding;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;

/** Builds the signed assertion a Google service account exchanges for an access token. Uses only the JDK. */
public final class GoogleServiceAccount {

    private GoogleServiceAccount() {}

    /** A JWT signed with RS256, valid for one hour, as described in Google's server to server OAuth flow. */
    public static String assertion(String clientEmail, PrivateKey key, String scope, String audience, Instant now) {
        String header = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}";
        String claims = "{\"iss\":" + com.premisave.wallet.commercial.util.Json.quote(clientEmail)
                + ",\"scope\":" + com.premisave.wallet.commercial.util.Json.quote(scope)
                + ",\"aud\":" + com.premisave.wallet.commercial.util.Json.quote(audience)
                + ",\"iat\":" + now.getEpochSecond()
                + ",\"exp\":" + now.plusSeconds(3600).getEpochSecond() + "}";
        String signingInput = b64(header.getBytes(StandardCharsets.UTF_8)) + "." + b64(claims.getBytes(StandardCharsets.UTF_8));
        try {
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(key);
            sig.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + b64(sig.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not sign the service account assertion", e);
        }
    }

    /** Reads the private_key value of a service account file: a PKCS8 PEM block. */
    public static PrivateKey parsePrivateKey(String pem) {
        try {
            String body = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(body);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("The service account private key could not be read", e);
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
