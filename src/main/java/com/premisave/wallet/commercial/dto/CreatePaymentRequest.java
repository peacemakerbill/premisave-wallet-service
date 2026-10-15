package com.premisave.wallet.commercial.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Map;

public record CreatePaymentRequest(
        @NotNull @DecimalMin(value = "0.01", message = "must be at least 0.01") BigDecimal amount,
        @Size(min = 3, max = 3, message = "must be a 3 letter currency code") String currency,
        @NotBlank @Size(max = 64) String merchantReference,
        @Size(max = 200) String description,
        @Valid CustomerInfo customer,
        @NotNull @Valid MethodSpec method,
        @Size(max = 40) String quoteId,
        @Size(max = 2048) String callbackUrl,
        Boolean notifyRegistered,
        @Min(60) @Max(86400) Integer expiresInSeconds,
        Map<String, String> metadata,
        @Size(max = 40) String paymentLinkId) {

    /** A payment that does not come from a payment link. */
    public CreatePaymentRequest(BigDecimal amount, String currency, String merchantReference, String description,
                                CustomerInfo customer, MethodSpec method, String quoteId, String callbackUrl,
                                Boolean notifyRegistered, Integer expiresInSeconds, Map<String, String> metadata) {
        this(amount, currency, merchantReference, description, customer, method, quoteId, callbackUrl,
                notifyRegistered, expiresInSeconds, metadata, null);
    }
}
