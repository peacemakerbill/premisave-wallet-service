package com.premisave.wallet.commercial.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestSignatureVerifierTest {

    private static final long NOW = 1_760_468_732L;
    private static final byte[] BODY = "{\"amount\":\"25.00\"}".getBytes(StandardCharsets.UTF_8);
    private static final String PATH = "/internal/commercial/v1/live/payments?x=1";

    private static String ts(long t) { return String.valueOf(t); }

    private static String sig(String secret, long t) {
        return RequestSignatureVerifier.sign(secret, ts(t), "POST", PATH, BODY);
    }

    @Test
    void acceptsAValidSignature() {
        assertTrue(RequestSignatureVerifier.verify(List.of("s1"), "POST", PATH, BODY, ts(NOW), sig("s1", NOW), NOW, 300).isEmpty());
    }

    @Test
    void acceptsTheSha256PrefixAndUpperCase() {
        String s = "sha256=" + sig("s1", NOW).toUpperCase();
        assertTrue(RequestSignatureVerifier.verify(List.of("s1"), "POST", PATH, BODY, ts(NOW), s, NOW, 300).isEmpty());
    }

    @Test
    void acceptsAnyConfiguredSecretForRotation() {
        assertTrue(RequestSignatureVerifier.verify(List.of("new", "old"), "POST", PATH, BODY, ts(NOW), sig("old", NOW), NOW, 300).isEmpty());
    }

    @Test
    void rejectsAWrongSecret() {
        assertEquals("Signature does not match",
                RequestSignatureVerifier.verify(List.of("s1"), "POST", PATH, BODY, ts(NOW), sig("other", NOW), NOW, 300).orElse(""));
    }

    @Test
    void rejectsATamperedBodyMethodOrPath() {
        String s = sig("s1", NOW);
        byte[] other = "{\"amount\":\"2500.00\"}".getBytes(StandardCharsets.UTF_8);
        assertTrue(RequestSignatureVerifier.verify(List.of("s1"), "POST", PATH, other, ts(NOW), s, NOW, 300).isPresent());
        assertTrue(RequestSignatureVerifier.verify(List.of("s1"), "PUT", PATH, BODY, ts(NOW), s, NOW, 300).isPresent());
        assertTrue(RequestSignatureVerifier.verify(List.of("s1"), "POST", PATH + "&y=2", BODY, ts(NOW), s, NOW, 300).isPresent());
    }

    @Test
    void rejectsOldAndFutureTimestamps() {
        long old = NOW - 301;
        long future = NOW + 301;
        assertTrue(RequestSignatureVerifier.verify(List.of("s1"), "POST", PATH, BODY, ts(old), sig("s1", old), NOW, 300).isPresent());
        assertTrue(RequestSignatureVerifier.verify(List.of("s1"), "POST", PATH, BODY, ts(future), sig("s1", future), NOW, 300).isPresent());
        long edge = NOW - 300;
        assertTrue(RequestSignatureVerifier.verify(List.of("s1"), "POST", PATH, BODY, ts(edge), sig("s1", edge), NOW, 300).isEmpty());
    }

    @Test
    void rejectsMissingOrMalformedHeaders() {
        assertTrue(RequestSignatureVerifier.verify(List.of("s1"), "POST", PATH, BODY, null, "abc", NOW, 300).isPresent());
        assertTrue(RequestSignatureVerifier.verify(List.of("s1"), "POST", PATH, BODY, ts(NOW), null, NOW, 300).isPresent());
        assertTrue(RequestSignatureVerifier.verify(List.of("s1"), "POST", PATH, BODY, "yesterday", "abc", NOW, 300).isPresent());
    }

    @Test
    void failsClosedWhenNoSecretIsConfigured() {
        assertTrue(RequestSignatureVerifier.verify(List.of(), "POST", PATH, BODY, ts(NOW), sig("s1", NOW), NOW, 300).isPresent());
    }

    @Test
    void emptyBodyIsSignedAsEmpty() {
        String s = RequestSignatureVerifier.sign("s1", ts(NOW), "GET", "/x", null);
        assertTrue(RequestSignatureVerifier.verify(List.of("s1"), "GET", "/x", new byte[0], ts(NOW), s, NOW, 300).isEmpty());
    }
}
