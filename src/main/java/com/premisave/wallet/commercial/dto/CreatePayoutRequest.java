package com.premisave.wallet.commercial.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Map;

/**
 * destination is a free form object whose required fields depend on its type;
 * DestinationValidator checks it and returns the cleaned version.
 */
public record CreatePayoutRequest(
        @NotNull @DecimalMin(value = "0.01", message = "must be at least 0.01") BigDecimal amount,
        @Size(min = 3, max = 3, message = "must be a 3 letter currency code") String currency,
        @NotBlank @Size(max = 64) String merchantReference,
        @Size(max = 100) String remarks,
        @NotNull Map<String, Object> destination,
        @Size(max = 30) String provider,
        @Size(max = 40) String quoteId,
        @Size(max = 2048) String callbackUrl,
        Boolean notifyRegistered,
        Map<String, String> metadata) {
}
