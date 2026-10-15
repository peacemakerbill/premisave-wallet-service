package com.premisave.wallet.commercial.rail.mpesa.c2b;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class C2bPayloadTest {

    private static Map<String, Object> body() {
        Map<String, Object> m = new HashMap<>();
        m.put("TransactionType", "Pay Bill");
        m.put("TransID", "RKTQDM7W6S");
        m.put("TransTime", "20261016063845");
        m.put("TransAmount", "1250.50");
        m.put("BusinessShortCode", "600123");
        m.put("BillRefNumber", "ps1234-inv001");
        m.put("MSISDN", "254712345678");
        m.put("FirstName", "Jane");
        m.put("MiddleName", "");
        m.put("LastName", "Doe");
        return m;
    }

    @Test
    void readsTheFieldsDarajaSends() {
        C2bPayload p = C2bPayload.parse(body());
        assertEquals("RKTQDM7W6S", p.transId());
        assertEquals(new BigDecimal("1250.50"), p.amount());
        assertEquals("600123", p.shortcode());
        assertEquals("ps1234-inv001", p.billRef());
        assertEquals("Jane Doe", p.payerName());
        assertEquals("254712345678", p.payerPhone());
    }

    @Test
    void aHashedNumberIsNotKept() {
        Map<String, Object> m = body();
        m.put("MSISDN", "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9");
        assertNull(C2bPayload.parse(m).payerPhone());
    }

    @Test
    void missingOrBadValuesDoNotThrow() {
        Map<String, Object> m = new HashMap<>();
        m.put("TransAmount", "abc");
        C2bPayload p = C2bPayload.parse(m);
        assertNull(p.amount());
        assertNull(p.transId());
        assertNull(p.payerName());
        assertNull(p.payerPhone());
    }
}
