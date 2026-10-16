package com.premisave.wallet.commercial.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MaskingTest {

    @Test
    void phone() {
        assertEquals("2547****5678", Masking.phone("254712345678"));
        assertEquals("***78", Masking.phone("12378"));
        assertNull(Masking.phone(null));
    }

    @Test
    void email() {
        assertEquals("j***@example.com", Masking.email("jane@example.com"));
        assertEquals("a@example.com", Masking.email("a@example.com"));
    }

    @Test
    void account() {
        assertEquals("0123****90", Masking.account("0123456790"));
        assertEquals("**34", Masking.account("1234"));
    }

    @Test
    void address() {
        assertEquals("0xAb...1234", Masking.address("0xAbCdEf0123456789abcdef0123456789abcd1234"));
        assertEquals("short", Masking.address("short"));
    }
}
