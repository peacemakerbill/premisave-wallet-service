package com.premisave.wallet.commercial.dto;

import com.premisave.wallet.commercial.enums.MethodType;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/**
 * Every field is optional and only the fields sent change. status can be ACTIVE or DISABLED.
 * amount, minAmount, maxAmount and currency are refused once any payment was attempted.
 */
public record UpdatePaymentLinkRequest(
        @Size(max = 200) String description,
        @Size(max = 20) String status,
        @Size(max = 40) String expiresAt,
        Set<MethodType> allowedMethods,
        @Size(max = 2048) String callbackUrl,
        Map<String, String> metadata,
        BigDecimal amount,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        @Size(min = 3, max = 3, message = "must be a 3 letter currency code") String currency) {
}
