package com.premisave.wallet.commercial.rail.stripe;

import com.premisave.wallet.commercial.util.Hashing;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StripeSignatureTest {

    private static final String SECRET = "whsec_test";
    private static final String BODY = "{\"id\":\"evt_1\"}";

    private static String header(long t, String body, String secret) {
        return "t=" + t + ",v1=" + Hashing.hmacSha256Hex(secret, t + "." + body);
    }

    @Test
    void acceptsAValidSignatureInsideTheWindow() {
        assertTrue(StripeSignature.verify(SECRET, header(1000, BODY, SECRET), BODY, 1100, 300));
    }

    @Test
    void acceptsWhenAnyOfSeveralSignaturesMatches() {
        String h = "t=1000,v1=deadbeef,v1=" + Hashing.hmacSha256Hex(SECRET, "1000." + BODY);
        assertTrue(StripeSignature.verify(SECRET, h, BODY, 1000, 300));
    }

    @Test
    void refusesAReplayOutsideTheWindow() {
        assertFalse(StripeSignature.verify(SECRET, header(1000, BODY, SECRET), BODY, 1400, 300));
        assertFalse(StripeSignature.verify(SECRET, header(1000, BODY, SECRET), BODY, 600, 300));
    }

    @Test
    void refusesATamperedBodyOrWrongSecret() {
        assertFalse(StripeSignature.verify(SECRET, header(1000, BODY, SECRET), BODY + " ", 1000, 300));
        assertFalse(StripeSignature.verify(SECRET, header(1000, BODY, "other"), BODY, 1000, 300));
    }

    @Test
    void refusesMissingOrMalformedInput() {
        assertFalse(StripeSignature.verify(SECRET, null, BODY, 1000, 300));
        assertFalse(StripeSignature.verify("", header(1000, BODY, SECRET), BODY, 1000, 300));
        assertFalse(StripeSignature.verify(SECRET, "garbage", BODY, 1000, 300));
        assertFalse(StripeSignature.verify(SECRET, "t=abc,v1=00", BODY, 1000, 300));
        assertFalse(StripeSignature.verify(SECRET, "t=1000", BODY, 1000, 300));
    }
}
