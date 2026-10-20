package com.premisave.wallet.commercial.service.link;

import com.premisave.wallet.commercial.entity.link.PaymentLink;
import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.enums.MethodType;
import com.premisave.wallet.commercial.exception.CommercialException;

import java.math.BigDecimal;
import java.time.Instant;

/** What a payment link allows. Pure, so the rules can be tested without a database. */
public final class PaymentLinkRules {

    public static final String ACTIVE = "ACTIVE";
    public static final String DISABLED = "DISABLED";
    public static final String EXPIRED = "EXPIRED";
    public static final String COMPLETED = "COMPLETED";

    private PaymentLinkRules() {}

    /**
     * The status shown to the merchant and customer. A disabled link stays disabled, a used up
     * single use link is completed, and an active link past its expiry is expired.
     */
    public static String effectiveStatus(PaymentLink link, Instant now) {
        if (link.getStatus() == PaymentLink.Status.DISABLED) return DISABLED;
        if (link.getStatus() == PaymentLink.Status.COMPLETED) return COMPLETED;
        if (link.getExpiresAt() != null && !link.getExpiresAt().isAfter(now)) return EXPIRED;
        return ACTIVE;
    }

    /**
     * Throws when a payment attempt breaks the link's rules. currency may be null when the
     * attempt did not name one (the link's currency is used).
     */
    public static void checkAttempt(PaymentLink link, Instant now, MethodType method, BigDecimal amount, String currency) {
        String status = effectiveStatus(link, now);
        if (!ACTIVE.equals(status)) {
            throw CommercialException.invalidState("This payment link is " + status.toLowerCase() + " and cannot take payments");
        }
        if (!link.getAllowedMethods().contains(method)) {
            throw new CommercialException(ErrorCode.METHOD_NOT_ENABLED, method + " is not allowed for this payment link");
        }
        if (currency != null && !currency.equalsIgnoreCase(link.getCurrency())) {
            throw CommercialException.validation("currency", "must be " + link.getCurrency() + " for this payment link");
        }
        if (amount == null) throw CommercialException.validation("amount", "is required");
        if (link.getAmountType() == PaymentLink.AmountType.FIXED) {
            if (amount.compareTo(link.getAmount()) != 0) {
                throw CommercialException.validation("amount", "must be " + link.getAmount().toPlainString() + " for this payment link");
            }
        } else {
            if (link.getMinAmount() != null && amount.compareTo(link.getMinAmount()) < 0) {
                throw CommercialException.validation("amount", "must be at least " + link.getMinAmount().toPlainString() + " for this payment link");
            }
            if (link.getMaxAmount() != null && amount.compareTo(link.getMaxAmount()) > 0) {
                throw CommercialException.validation("amount", "must be at most " + link.getMaxAmount().toPlainString() + " for this payment link");
            }
        }
    }

    /** True when the amounts describe a coherent link. Throws a validation error naming the first problem. */
    public static void checkShape(PaymentLink.AmountType type, BigDecimal amount, BigDecimal min, BigDecimal max) {
        BigDecimal floor = new BigDecimal("0.01");
        if (type == PaymentLink.AmountType.FIXED) {
            if (amount == null) throw CommercialException.validation("amount", "is required for a FIXED link");
            if (amount.compareTo(floor) < 0) throw CommercialException.validation("amount", "must be at least 0.01");
            if (min != null || max != null) {
                throw CommercialException.validation("minAmount", "and maxAmount are only for OPEN links");
            }
        } else {
            if (amount != null) throw CommercialException.validation("amount", "is only for FIXED links; use minAmount and maxAmount");
            if (min != null && min.compareTo(floor) < 0) throw CommercialException.validation("minAmount", "must be at least 0.01");
            if (max != null && max.compareTo(floor) < 0) throw CommercialException.validation("maxAmount", "must be at least 0.01");
            if (min != null && max != null && max.compareTo(min) < 0) {
                throw CommercialException.validation("maxAmount", "must not be less than minAmount");
            }
        }
    }
}
