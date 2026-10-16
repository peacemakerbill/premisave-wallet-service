package com.premisave.wallet.commercial.security;

import com.premisave.wallet.commercial.enums.AppStatus;
import com.premisave.wallet.commercial.enums.Environment;
import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.enums.MethodType;
import com.premisave.wallet.commercial.exception.CommercialException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The header values are a contract with the developer service. The same literals are asserted in the developer
 * service's AppContextHeadersTest, so a change on one side fails a test on the other.
 */
class AppContextHeadersTest {

    private static Map<String, String> base() {
        Map<String, String> h = new HashMap<>();
        h.put("X-Merchant-Id", "mer_xyz");
        h.put("X-App-Id", "app_abc");
        h.put("X-App-Environment", "production");
        h.put("X-App-Status", "ACTIVE");
        h.put("X-App-Version", "1760601600000");
        return h;
    }

    private static Function<String, String> of(Map<String, String> m) {
        return m::get;
    }

    private static AppContext parse(Map<String, String> h) {
        return AppContextHeaders.parse(of(h), Environment.LIVE);
    }

    @Test
    void identityStatusAndVersionAreRead() {
        AppContext c = parse(base());
        assertEquals("app_abc", c.appId());
        assertEquals("mer_xyz", c.merchantId());
        assertEquals(Environment.LIVE, c.environment());
        assertEquals(AppStatus.ACTIVE, c.status());
        assertTrue(c.active());
        assertEquals(1760601600000L, c.version());
        assertNull(c.enabledMethods());
        assertNull(c.limits());
    }

    @Test
    void suspendedIsRead() {
        Map<String, String> h = base();
        h.put("X-App-Status", "suspended");
        assertFalse(parse(h).active());
    }

    @Test
    void methodsMatchWhatTheDeveloperServiceSends() {
        Map<String, String> h = base();
        h.put("X-App-Methods", "CARD,MOBILE_MONEY");
        assertEquals(EnumSet.of(MethodType.CARD, MethodType.MOBILE_MONEY), parse(h).enabledMethods());
        h.put("X-App-Methods", "none");
        assertTrue(parse(h).enabledMethods().isEmpty());
    }

    @Test
    void limitsMatchWhatTheDeveloperServiceSends() {
        Map<String, String> h = base();
        h.put("X-App-Limits", "singleTransactionUsd=100.00,monthlyVolumeUsd=20000,dailyPayoutCount=5");
        AppContext c = parse(h);
        assertEquals(new BigDecimal("100.00"), c.limits().getSingleTransactionUsd());
        assertNull(c.limits().getDailyVolumeUsd());
        assertEquals(new BigDecimal("20000"), c.limits().getMonthlyVolumeUsd());
        assertEquals(5, c.limits().getDailyPayoutCount());
    }

    @Test
    void requiredHeadersMustBePresent() {
        for (String name : new String[] {"X-Merchant-Id", "X-App-Id", "X-App-Status", "X-App-Version"}) {
            Map<String, String> h = base();
            h.remove(name);
            CommercialException e = assertThrows(CommercialException.class, () -> parse(h));
            assertEquals(ErrorCode.VALIDATION_FAILED, e.code(), name);
        }
    }

    @Test
    void invalidValuesAreRefused() {
        String[][] bad = {{"X-App-Status", "DELETED"}, {"X-App-Version", "abc"}, {"X-App-Methods", "CARD,TELEPATHY"},
                {"X-App-Limits", "singleTransactionUsd=abc"}, {"X-App-Limits", "singleTransactionUsd=-5"}, {"X-App-Limits", "dailyPayoutCount=0"},
                {"X-App-Limits", "unknownLimit=5"}, {"X-App-Limits", "singleTransactionUsd"}, {"X-App-Limits", "singleTransactionUsd="}};
        for (String[] b : bad) {
            Map<String, String> h = base();
            h.put(b[0], b[1]);
            CommercialException e = assertThrows(CommercialException.class, () -> parse(h), b[0] + "=" + b[1]);
            assertEquals(ErrorCode.VALIDATION_FAILED, e.code());
        }
    }
}
