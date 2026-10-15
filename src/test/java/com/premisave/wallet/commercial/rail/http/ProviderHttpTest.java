package com.premisave.wallet.commercial.rail.http;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ProviderHttpTest {

    @Test
    void formEncodesInOrderAndSkipsNulls() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("amount", "1050");
        m.put("metadata[a b]", "x&y=z");
        m.put("skipped", null);
        assertEquals("amount=1050&metadata%5Ba+b%5D=x%26y%3Dz", ProviderHttp.form(m));
    }

    @Test
    void basicAuthHeader() {
        assertEquals("Basic dXNlcjpwYXNz", ProviderHttp.basic("user", "pass"));
    }

    @Test
    void headersDropMissingValues() {
        Map<String, String> h = ProviderHttp.headers("A", "1", "B", null, "C", "3");
        assertEquals(2, h.size());
        assertFalse(h.containsKey("B"));
    }
}
