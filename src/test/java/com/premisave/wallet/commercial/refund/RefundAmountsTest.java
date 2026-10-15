package com.premisave.wallet.commercial.refund;

import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.exception.CommercialException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RefundAmountsTest {

    // A 25.00 USD payment that the customer made as KES 3225.00 (rate 129).
    private static final long PAID = 2500;
    private static final BigDecimal PAID_KES = new BigDecimal("3225.00");

    private static RefundAmounts.Resolved resolve(long remaining, String amount, String currency) {
        return RefundAmounts.resolve(PAID, PAID_KES, "KES", remaining,
                amount == null ? null : new BigDecimal(amount), currency);
    }

    @Test
    void noAmountMeansEverythingThatIsLeft() {
        RefundAmounts.Resolved r = resolve(PAID, null, null);
        assertEquals(2500L, r.usdCents());
        assertEquals("3225.00", r.nativeAmount().toPlainString());
    }

    @Test
    void whatIsLeftAfterAPartialRefundKeepsTheOriginalRate() {
        RefundAmounts.Resolved r = resolve(1500, null, null);
        assertEquals(1500L, r.usdCents());
        assertEquals("1935.00", r.nativeAmount().toPlainString());
    }

    @Test
    void aUsdAmountFollowsThePaymentsOwnProportion() {
        RefundAmounts.Resolved r = resolve(PAID, "10.00", "usd");
        assertEquals(1000L, r.usdCents());
        assertEquals("1290.00", r.nativeAmount().toPlainString());
    }

    @Test
    void anAmountInThePaymentsCurrencyKeepsThatFigureExactly() {
        RefundAmounts.Resolved r = resolve(PAID, "1000", "KES");
        assertEquals("1000", r.nativeAmount().toPlainString());
        assertEquals(775L, r.usdCents());
    }

    @Test
    void refundingAllTheNativeAmountThatIsLeftTakesExactlyTheRemainingUsd() {
        // 645 KES is 5.00 USD at the original rate; the rest of the payment is 20.00 USD.
        RefundAmounts.Resolved r = RefundAmounts.resolve(PAID, PAID_KES, "KES", 2000, new BigDecimal("2580.00"), "KES");
        assertEquals(2000L, r.usdCents());
    }

    @Test
    void moreThanIsLeftIsALimitError() {
        CommercialException e = assertThrows(CommercialException.class, () -> resolve(1500, "15.01", "USD"));
        assertEquals(ErrorCode.LIMIT_EXCEEDED, e.code());
        assertEquals("15.00", e.extra().get("refundable"));
        CommercialException n = assertThrows(CommercialException.class, () -> resolve(1500, "1935.01", "KES"));
        assertEquals(ErrorCode.LIMIT_EXCEEDED, n.code());
    }

    @Test
    void aFullyRefundedPaymentIsAnInvalidState() {
        CommercialException e = assertThrows(CommercialException.class, () -> resolve(0, null, null));
        assertEquals(ErrorCode.INVALID_STATE, e.code());
    }

    @Test
    void anAmountNeedsACurrencyAndItMustBeOneOfTheTwoKnown() {
        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(CommercialException.class, () -> resolve(PAID, "5.00", null)).code());
        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(CommercialException.class, () -> resolve(PAID, "5.00", "EUR")).code());
    }

    @Test
    void usdAmountsAreWholeCents() {
        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(CommercialException.class, () -> resolve(PAID, "1.005", "USD")).code());
        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(CommercialException.class, () -> resolve(PAID, "0", "USD")).code());
    }

    @Test
    void aPaymentWithoutANativeAmountCanOnlyBeRefundedInUsd() {
        RefundAmounts.Resolved r = RefundAmounts.resolve(1000, null, null, 1000, new BigDecimal("4.00"), "USD");
        assertEquals(400L, r.usdCents());
        assertNull(r.nativeAmount());
        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(CommercialException.class,
                () -> RefundAmounts.resolve(1000, null, null, 1000, new BigDecimal("4.00"), "KES")).code());
    }

    @Test
    void theLastCentIsNeverLostToRounding() {
        // Three equal partial refunds of a 10.00 payment that was 1000 units of a weak currency.
        BigDecimal paid = new BigDecimal("1000");
        long left = 1000;
        long total = 0;
        for (int i = 0; i < 2; i++) {
            RefundAmounts.Resolved r = RefundAmounts.resolve(1000, paid, "XYZ", left, new BigDecimal("333"), "XYZ");
            left -= r.usdCents();
            total += r.usdCents();
        }
        RefundAmounts.Resolved last = RefundAmounts.resolve(1000, paid, "XYZ", left, null, null);
        assertEquals(1000L, total + last.usdCents());
    }
}
