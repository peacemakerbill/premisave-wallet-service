package com.premisave.wallet.commercial.dto.link;

import com.premisave.wallet.commercial.entity.link.PaymentLink;
import com.premisave.wallet.commercial.enums.MethodType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/**
 * amountType defaults to FIXED when amount is given and OPEN otherwise. allowedMethods defaults
 * to every method enabled for the app. usage defaults to MULTIPLE. expiresAt is an ISO 8601 UTC timestamp.
 */
public record CreatePaymentLinkRequest(
        @NotBlank @Size(max = 64) String merchantReference,
        @Size(max = 200) String description,
        PaymentLink.AmountType amountType,
        BigDecimal amount,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        @Size(min = 3, max = 3, message = "must be a 3 letter currency code") String currency,
        Set<MethodType> allowedMethods,
        PaymentLink.Usage usage,
        @Size(max = 2048) String callbackUrl,
        @Size(max = 40) String expiresAt,
        Map<String, String> metadata) {
}
