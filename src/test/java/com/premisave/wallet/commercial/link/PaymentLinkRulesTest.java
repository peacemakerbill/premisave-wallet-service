package com.premisave.wallet.commercial.link;

import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.enums.MethodType;
import com.premisave.wallet.commercial.exception.CommercialException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaymentLinkRulesTest {

    private static final Instant NOW = Instant.parse("2026-10-15T10:00:00Z");

    private static PaymentLink fixed(String amount) {
        PaymentLink l = new PaymentLink();
        l.setId("plink_1");
        l.setAmountType(PaymentLink.AmountType.FIXED);
        l.setAmount(new BigDecimal(amount));
        l.setCurrency("USD");
        l.setAllowedMethods(EnumSet.of(MethodType.MOBILE_MONEY, MethodType.CARD));
        return l;
    }

    private static PaymentLink open(String min, String max) {
        PaymentLink l = fixed("1.00");
        l.setAmountType(PaymentLink.AmountType.OPEN);
        l.setAmount(null);
        l.setMinAmount(min == null ? null : new BigDecimal(min));
        l.setMaxAmount(max == null ? null : new BigDecimal(max));
        return l;
    }

    private static ErrorCode codeOf(Runnable r) {
        return assertThrows(CommercialException.class, r::run).code();
    }

    @Test
    void anActiveLinkWithNoExpiryIsActive() {
        assertEquals("ACTIVE", PaymentLinkRules.effectiveStatus(fixed("10.00"), NOW));
    }

    @Test
    void disabledBeatsEverythingElse() {
        PaymentLink l = fixed("10.00");
        l.setStatus(PaymentLink.Status.DISABLED);
        l.setExpiresAt(NOW.minusSeconds(5));
        assertEquals("DISABLED", PaymentLinkRules.effectiveStatus(l, NOW));
    }

    @Test
    void aUsedUpSingleUseLinkIsCompletedEvenAfterExpiry() {
        PaymentLink l = fixed("10.00");
        l.setStatus(PaymentLink.Status.COMPLETED);
        l.setExpiresAt(NOW.minusSeconds(5));
        assertEquals("COMPLETED", PaymentLinkRules.effectiveStatus(l, NOW));
    }

    @Test
    void anActiveLinkPastItsExpiryReadsAsExpired() {
        PaymentLink l = fixed("10.00");
        l.setExpiresAt(NOW.minusSeconds(1));
        assertEquals("EXPIRED", PaymentLinkRules.effectiveStatus(l, NOW));
        l.setExpiresAt(NOW);
        assertEquals("EXPIRED", PaymentLinkRules.effectiveStatus(l, NOW), "the expiry instant itself is already too late");
        l.setExpiresAt(NOW.plusSeconds(1));
        assertEquals("ACTIVE", PaymentLinkRules.effectiveStatus(l, NOW));
    }

    @Test
    void aFixedLinkTakesExactlyItsAmountInItsCurrency() {
        PaymentLink l = fixed("25.00");
        PaymentLinkRules.checkAttempt(l, NOW, MethodType.MOBILE_MONEY, new BigDecimal("25.00"), "USD");
        PaymentLinkRules.checkAttempt(l, NOW, MethodType.MOBILE_MONEY, new BigDecimal("25"), null);
        assertEquals(ErrorCode.VALIDATION_FAILED, codeOf(() ->
                PaymentLinkRules.checkAttempt(l, NOW, MethodType.MOBILE_MONEY, new BigDecimal("24.99"), "USD")));
        assertEquals(ErrorCode.VALIDATION_FAILED, codeOf(() ->
                PaymentLinkRules.checkAttempt(l, NOW, MethodType.MOBILE_MONEY, new BigDecimal("25.00"), "KES")));
    }

    @Test
    void aMethodOutsideTheAllowedListIsRefusedAsNotEnabled() {
        assertEquals(ErrorCode.METHOD_NOT_ENABLED, codeOf(() ->
                PaymentLinkRules.checkAttempt(fixed("5.00"), NOW, MethodType.PAYPAL, new BigDecimal("5.00"), "USD")));
    }

    @Test
    void aLinkThatIsNotActiveRefusesPaymentsAsInvalidState() {
        PaymentLink l = fixed("5.00");
        l.setStatus(PaymentLink.Status.DISABLED);
        assertEquals(ErrorCode.INVALID_STATE, codeOf(() ->
                PaymentLinkRules.checkAttempt(l, NOW, MethodType.CARD, new BigDecimal("5.00"), "USD")));
        PaymentLink expired = fixed("5.00");
        expired.setExpiresAt(NOW.minusSeconds(60));
        assertEquals(ErrorCode.INVALID_STATE, codeOf(() ->
                PaymentLinkRules.checkAttempt(expired, NOW, MethodType.CARD, new BigDecimal("5.00"), "USD")));
    }

    @Test
    void anOpenLinkEnforcesItsRange() {
        PaymentLink l = open("5.00", "50.00");
        PaymentLinkRules.checkAttempt(l, NOW, MethodType.CARD, new BigDecimal("5.00"), "USD");
        PaymentLinkRules.checkAttempt(l, NOW, MethodType.CARD, new BigDecimal("50.00"), "USD");
        assertEquals(ErrorCode.VALIDATION_FAILED, codeOf(() ->
                PaymentLinkRules.checkAttempt(l, NOW, MethodType.CARD, new BigDecimal("4.99"), "USD")));
        assertEquals(ErrorCode.VALIDATION_FAILED, codeOf(() ->
                PaymentLinkRules.checkAttempt(l, NOW, MethodType.CARD, new BigDecimal("50.01"), "USD")));
    }

    @Test
    void anOpenLinkWithNoBoundsTakesAnyAmount() {
        PaymentLinkRules.checkAttempt(open(null, null), NOW, MethodType.CARD, new BigDecimal("9999.99"), "USD");
    }

    @Test
    void shapeRulesKeepFixedAndOpenApart() {
        PaymentLinkRules.checkShape(PaymentLink.AmountType.FIXED, new BigDecimal("1.00"), null, null);
        PaymentLinkRules.checkShape(PaymentLink.AmountType.OPEN, null, new BigDecimal("1.00"), new BigDecimal("2.00"));
        PaymentLinkRules.checkShape(PaymentLink.AmountType.OPEN, null, null, null);
        assertThrows(CommercialException.class, () ->
                PaymentLinkRules.checkShape(PaymentLink.AmountType.FIXED, null, null, null));
        assertThrows(CommercialException.class, () ->
                PaymentLinkRules.checkShape(PaymentLink.AmountType.FIXED, new BigDecimal("0.00"), null, null));
        assertThrows(CommercialException.class, () ->
                PaymentLinkRules.checkShape(PaymentLink.AmountType.FIXED, new BigDecimal("5.00"), new BigDecimal("1.00"), null));
        assertThrows(CommercialException.class, () ->
                PaymentLinkRules.checkShape(PaymentLink.AmountType.OPEN, new BigDecimal("5.00"), null, null));
        assertThrows(CommercialException.class, () ->
                PaymentLinkRules.checkShape(PaymentLink.AmountType.OPEN, null, new BigDecimal("9.00"), new BigDecimal("3.00")));
    }
}
