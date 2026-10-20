package com.premisave.wallet.commercial.service.refund;

import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.util.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * Works out what a refund is worth in USD cents and in the customer's own currency.
 *
 * The customer gets back what they paid, so a refund uses the payment's original exchange rate
 * rather than today's. A refund asked for in the payment's own currency keeps that figure exactly
 * (a customer who paid KES 3,225 and is refunded KES 1,000 receives KES 1,000), and the USD side
 * follows from the payment's own USD to native proportion. The last refund of a payment always
 * takes exactly the USD that is left, so rounding can never leave a cent behind or ask for one more.
 */
public final class RefundAmounts {

    private RefundAmounts() {}

    public record Resolved(long usdCents, BigDecimal nativeAmount) {}

    /**
     * @param paymentUsd      the payment's amount in USD cents
     * @param paymentNative   the amount the customer paid in their own currency, or null when unknown
     * @param nativeCurrency  that currency, or null
     * @param remainingUsd    what can still be refunded, in USD cents
     * @param amount          the requested amount, or null for everything that is left
     * @param currency        the currency of the requested amount (required when an amount is given)
     */
    public static Resolved resolve(long paymentUsd, BigDecimal paymentNative, String nativeCurrency, long remainingUsd,
                                   BigDecimal amount, String currency) {
        if (remainingUsd <= 0) {
            throw CommercialException.invalidState("This payment has already been refunded in full");
        }
        if (amount == null) {
            return new Resolved(remainingUsd, nativeFor(paymentUsd, paymentNative, remainingUsd));
        }
        if (amount.signum() <= 0) throw CommercialException.validation("amount", "must be greater than zero");
        if (currency == null || currency.isBlank()) {
            throw CommercialException.validation("currency", "is required when an amount is given");
        }
        String cur = currency.trim().toUpperCase(Locale.ROOT);

        if (cur.equals(Money.BASE_CURRENCY)) {
            if (Money.hasMoreThanCents(amount)) throw CommercialException.validation("amount", "must have at most two decimal places");
            long usd = Money.toCents(amount);
            if (usd < 1) throw CommercialException.validation("amount", "must be at least 0.01");
            if (usd > remainingUsd) throw tooMuch(remainingUsd);
            return new Resolved(usd, nativeFor(paymentUsd, paymentNative, usd));
        }

        boolean paidInThisCurrency = nativeCurrency != null && cur.equals(nativeCurrency.toUpperCase(Locale.ROOT))
                && paymentNative != null && paymentNative.signum() > 0;
        if (!paidInThisCurrency) {
            boolean hasNative = nativeCurrency != null && !nativeCurrency.equalsIgnoreCase(Money.BASE_CURRENCY);
            throw CommercialException.validation("currency", hasNative
                    ? "must be USD or " + nativeCurrency.toUpperCase(Locale.ROOT) + ", the currency of the payment"
                    : "must be USD, the currency of the payment");
        }

        BigDecimal remainingNative = nativeFor(paymentUsd, paymentNative, remainingUsd);
        int order = amount.compareTo(remainingNative);
        if (order > 0) throw tooMuch(remainingUsd);
        long usd;
        if (order == 0) {
            usd = remainingUsd;
        } else {
            usd = amount.multiply(BigDecimal.valueOf(paymentUsd)).divide(paymentNative, 0, RoundingMode.HALF_UP).longValueExact();
            usd = Math.min(usd, remainingUsd);
        }
        if (usd < 1) throw CommercialException.validation("amount", "is too small to refund");
        return new Resolved(usd, amount);
    }

    /** The share of the customer's own amount that a USD figure stands for. Null when the payment has no native amount. */
    static BigDecimal nativeFor(long paymentUsd, BigDecimal paymentNative, long usd) {
        if (paymentNative == null) return null;
        if (usd == paymentUsd) return paymentNative;
        return paymentNative.multiply(BigDecimal.valueOf(usd))
                .divide(BigDecimal.valueOf(paymentUsd), Math.max(paymentNative.scale(), 0), RoundingMode.HALF_UP);
    }

    private static CommercialException tooMuch(long remainingUsd) {
        return new CommercialException(ErrorCode.LIMIT_EXCEEDED, "Amount exceeds the refundable balance of this payment")
                .with("refundable", Money.format(remainingUsd)).with("currency", Money.BASE_CURRENCY);
    }
}
