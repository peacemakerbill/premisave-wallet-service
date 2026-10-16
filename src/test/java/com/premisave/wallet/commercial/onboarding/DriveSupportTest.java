package com.premisave.wallet.commercial.onboarding;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DriveSupportTest {

    private static KeyPair keys() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        return g.generateKeyPair();
    }

    private static String pem(PrivateKey k) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(k.getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";
    }

    private static String decode(String part) {
        return new String(Base64.getUrlDecoder().decode(part), StandardCharsets.UTF_8);
    }

    @Test
    void privateKeyIsReadFromAServiceAccountPem() throws Exception {
        KeyPair pair = keys();
        PrivateKey parsed = GoogleServiceAccount.parsePrivateKey(pem(pair.getPrivate()));
        assertEquals(pair.getPrivate(), parsed);
    }

    @Test
    void anUnreadableKeyIsReportedClearly() {
        try {
            GoogleServiceAccount.parsePrivateKey("-----BEGIN PRIVATE KEY-----\nnot a key\n-----END PRIVATE KEY-----");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("private key"));
            return;
        }
        throw new AssertionError("expected IllegalStateException");
    }

    @Test
    void theAssertionIsASignedRs256Jwt() throws Exception {
        KeyPair pair = keys();
        Instant now = Instant.parse("2026-10-16T09:00:00Z");
        String jwt = GoogleServiceAccount.assertion("svc@project.iam.gserviceaccount.com", pair.getPrivate(),
                "https://www.googleapis.com/auth/drive.file", "https://oauth2.googleapis.com/token", now);
        String[] parts = jwt.split("\\.");
        assertEquals(3, parts.length);
        assertEquals("{\"alg\":\"RS256\",\"typ\":\"JWT\"}", decode(parts[0]));
        String claims = decode(parts[1]);
        assertTrue(claims.contains("\"iss\":\"svc@project.iam.gserviceaccount.com\""));
        assertTrue(claims.contains("\"scope\":\"https://www.googleapis.com/auth/drive.file\""));
        assertTrue(claims.contains("\"aud\":\"https://oauth2.googleapis.com/token\""));
        assertTrue(claims.contains("\"iat\":" + now.getEpochSecond()));
        assertTrue(claims.contains("\"exp\":" + (now.getEpochSecond() + 3600)));

        Signature verify = Signature.getInstance("SHA256withRSA");
        verify.initVerify(pair.getPublic());
        verify.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(verify.verify(Base64.getUrlDecoder().decode(parts[2])), "signature must verify with the public key");
        assertTrue(!jwt.contains("=") && !jwt.contains("+") && !jwt.contains("/"), "base64url without padding");
    }

    @Test
    void uploadBodyHoldsMetadataThenTheFileBytes() {
        byte[] file = {1, 2, 3, (byte) 0xFF, 0, 'x'};
        byte[] body = DriveMultipart.body("BOUNDARY", "{\"name\":\"a.pdf\"}", "application/pdf", file);
        String text = new String(body, StandardCharsets.ISO_8859_1);
        assertTrue(text.startsWith("--BOUNDARY\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n{\"name\":\"a.pdf\"}\r\n"));
        assertTrue(text.contains("--BOUNDARY\r\nContent-Type: application/pdf\r\n\r\n"));
        assertTrue(text.endsWith("\r\n--BOUNDARY--\r\n"));
        int start = text.indexOf("application/pdf\r\n\r\n") + "application/pdf\r\n\r\n".length();
        for (int i = 0; i < file.length; i++) assertEquals(file[i], body[start + i]);
        assertEquals("multipart/related; boundary=BOUNDARY", DriveMultipart.contentType("BOUNDARY"));
    }

    @Test
    void unconfiguredStorageRefusesEverything() {
        DocumentStorage s = new UnconfiguredDocumentStorage();
        assertTrue(!s.available());
        for (Runnable r : new Runnable[] {() -> s.upload("a", "application/pdf", new byte[1]), () -> s.download("x"), () -> s.delete("x")}) {
            try {
                r.run();
            } catch (com.premisave.wallet.commercial.exception.CommercialException e) {
                assertEquals(com.premisave.wallet.commercial.enums.ErrorCode.PROVIDER_UNAVAILABLE, e.code());
                continue;
            }
            throw new AssertionError("expected CommercialException");
        }
    }
}
