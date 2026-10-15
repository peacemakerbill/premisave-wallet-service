package com.premisave.wallet.commercial.rail.mpesa.c2b;

import com.premisave.wallet.commercial.link.AccountReference;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class C2bRulesTest {

    private static final Instant NOW = Instant.parse("2026-10-16T10:00:00Z");
    private static final String SHORTCODE = "600123";

    private static AccountReference ref() {
        AccountReference r = new AccountReference();
        r.setReference("PS1234-INV001");
        r.setLivemode(true);
        r.setMethodType("MOBILE_MONEY");
        r.setShortcode(SHORTCODE);
        r.setStatus("OPEN");
        return r;
    }

    @Test
    void whatCustomersTypeIsNormalised() {
        assertEquals("PS1234-INV001", C2bRules.normaliseReference("  ps1234 - inv001 "));
        assertEquals("PS1234-INV001", C2bRules.normaliseReference("PS1234-INV001"));
        assertEquals("", C2bRules.normaliseReference(null));
    }

    @Test
    void referenceShapeIsRecognised() {
        assertTrue(C2bRules.looksLikeReference("PS1234-INV001"));
        assertTrue(C2bRules.looksLikeReference("PS123456-A-B-9"));
        assertFalse(C2bRules.looksLikeReference("254712345678"));
        assertFalse(C2bRules.looksLikeReference("PS12-X"));
        assertFalse(C2bRules.looksLikeReference("PS1234-"));
        assertFalse(C2bRules.looksLikeReference(null));
    }

    @Test
    void onlyALiveMobileMoneyReferenceForThisPaybillIsUsable() {
        assertTrue(C2bRules.usable(ref(), SHORTCODE));
        assertFalse(C2bRules.usable(null, SHORTCODE));
        assertFalse(C2bRules.usable(ref(), "999999"));
        AccountReference test = ref();
        test.setLivemode(false);
        assertFalse(C2bRules.usable(test, SHORTCODE));
        AccountReference other = ref();
        other.setMethodType("CARD");
        assertFalse(C2bRules.usable(other, SHORTCODE));
    }

    @Test
    void referenceStates() {
        assertEquals(C2bRules.Flag.NONE, C2bRules.state(ref(), NOW));
        AccountReference closed = ref();
        closed.setStatus("CLOSED");
        assertEquals(C2bRules.Flag.REFERENCE_CLOSED, C2bRules.state(closed, NOW));
        AccountReference expired = ref();
        expired.setExpiresAt(NOW.minusSeconds(1));
        assertEquals(C2bRules.Flag.REFERENCE_EXPIRED, C2bRules.state(expired, NOW));
        expired.setExpiresAt(NOW.plusSeconds(1));
        assertEquals(C2bRules.Flag.NONE, C2bRules.state(expired, NOW));
    }

    @Test
    void amountsInShillingsMustMatchExactly() {
        assertTrue(C2bRules.exactAmount(new BigDecimal("1000"), new BigDecimal("1000.00")));
        assertFalse(C2bRules.exactAmount(new BigDecimal("1000"), new BigDecimal("999.99")));
        assertFalse(C2bRules.exactAmount(null, new BigDecimal("1")));
    }

    @Test
    void usdAmountsAllowASmallTolerance() {
        assertTrue(C2bRules.usdWithinTolerance(10_000, 10_000));
        assertTrue(C2bRules.usdWithinTolerance(10_000, 9_900));
        assertTrue(C2bRules.usdWithinTolerance(10_000, 10_100));
        assertFalse(C2bRules.usdWithinTolerance(10_000, 10_101));
        assertFalse(C2bRules.usdWithinTolerance(10_000, 9_899));
        assertTrue(C2bRules.usdWithinTolerance(100, 102));
        assertFalse(C2bRules.usdWithinTolerance(100, 103));
    }

    @Test
    void validationAcceptsAGoodPayment() {
        C2bRules.Verdict v = C2bRules.validation(ref(), SHORTCODE, NOW, true);
        assertTrue(v.accept());
        assertEquals("0", v.code());
    }

    @Test
    void validationRefusesWhatCannotBeCreditedProperly() {
        assertEquals(C2bRules.INVALID_ACCOUNT, C2bRules.validation(null, SHORTCODE, NOW, true).code());
        assertEquals(C2bRules.INVALID_ACCOUNT, C2bRules.validation(ref(), "999999", NOW, true).code());
        AccountReference expired = ref();
        expired.setExpiresAt(NOW.minusSeconds(5));
        C2bRules.Verdict v = C2bRules.validation(expired, SHORTCODE, NOW, true);
        assertFalse(v.accept());
        assertEquals("This account number has expired", v.reason());
        AccountReference closed = ref();
        closed.setStatus("CLOSED");
        assertEquals("This account number is closed", C2bRules.validation(closed, SHORTCODE, NOW, true).reason());
        C2bRules.Verdict amount = C2bRules.validation(ref(), SHORTCODE, NOW, false);
        assertFalse(amount.accept());
        assertEquals(C2bRules.INVALID_AMOUNT, amount.code());
    }

    @Test
    void confirmationCreditsButFlags() {
        assertEquals(C2bRules.Flag.NONE, C2bRules.confirmationFlag(ref(), NOW, true));
        assertEquals(C2bRules.Flag.AMOUNT_MISMATCH, C2bRules.confirmationFlag(ref(), NOW, false));
        AccountReference expired = ref();
        expired.setExpiresAt(NOW.minusSeconds(5));
        assertEquals(C2bRules.Flag.REFERENCE_EXPIRED, C2bRules.confirmationFlag(expired, NOW, false));
        AccountReference closed = ref();
        closed.setStatus("CLOSED");
        assertEquals(C2bRules.Flag.REFERENCE_CLOSED, C2bRules.confirmationFlag(closed, NOW, true));
    }
}
