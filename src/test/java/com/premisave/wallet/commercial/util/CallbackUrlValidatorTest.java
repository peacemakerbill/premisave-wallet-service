package com.premisave.wallet.commercial.util;

import com.premisave.wallet.commercial.enums.Environment;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallbackUrlValidatorTest {

    private static boolean ok(String url, Environment env) {
        return CallbackUrlValidator.check(url, env).isEmpty();
    }

    @Test
    void liveAcceptsPublicHttps() {
        assertTrue(ok("https://merchant.example.com/hooks/premisave", Environment.LIVE));
    }

    @Test
    void liveRejectsHttp() {
        assertFalse(ok("http://merchant.example.com/hook", Environment.LIVE));
    }

    @Test
    void liveRejectsLocalAndPrivateTargets() {
        assertFalse(ok("https://localhost/hook", Environment.LIVE));
        assertFalse(ok("https://app.localhost/hook", Environment.LIVE));
        assertFalse(ok("https://shop.internal/hook", Environment.LIVE));
        assertFalse(ok("https://127.0.0.1/hook", Environment.LIVE));
        assertFalse(ok("https://10.1.2.3/hook", Environment.LIVE));
        assertFalse(ok("https://172.16.0.1/hook", Environment.LIVE));
        assertFalse(ok("https://192.168.1.1/hook", Environment.LIVE));
        assertFalse(ok("https://169.254.169.254/latest/meta-data", Environment.LIVE));
        assertFalse(ok("https://[::1]/hook", Environment.LIVE));
    }

    @Test
    void liveAcceptsPublicIpv4Literal() {
        assertTrue(ok("https://8.8.8.8/hook", Environment.LIVE));
        assertTrue(ok("https://172.32.0.1/hook", Environment.LIVE));
    }

    @Test
    void rejectsCredentialsFragmentAndMissingHost() {
        assertFalse(ok("https://user:pw@merchant.example.com/hook", Environment.LIVE));
        assertFalse(ok("https://merchant.example.com/hook#frag", Environment.LIVE));
        assertFalse(ok("https:///hook", Environment.LIVE));
        assertFalse(ok("not a url", Environment.LIVE));
        assertFalse(ok("", Environment.LIVE));
        assertFalse(ok(null, Environment.SANDBOX));
    }

    @Test
    void sandboxAllowsHttpAndLocalhost() {
        assertTrue(ok("http://localhost:3000/hook", Environment.SANDBOX));
        assertTrue(ok("https://abc.ngrok.app/hook", Environment.SANDBOX));
    }

    @Test
    void sandboxStillRejectsOtherSchemes() {
        assertFalse(ok("ftp://example.com/hook", Environment.SANDBOX));
    }

    @Test
    void rejectsOverlongUrls() {
        String url = "https://merchant.example.com/" + "a".repeat(2100);
        assertFalse(ok(url, Environment.LIVE));
    }
}
