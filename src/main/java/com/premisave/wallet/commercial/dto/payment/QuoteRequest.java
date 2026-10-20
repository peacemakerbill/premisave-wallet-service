package com.premisave.wallet.commercial.dto.payment;

import com.premisave.wallet.commercial.enums.Direction;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Map;

/** Prices a collection (give method) or a payout (give destination) without creating anything. */
public record QuoteRequest(
        @NotNull Direction direction,
        @NotNull @DecimalMin(value = "0.01", message = "must be at least 0.01") BigDecimal amount,
        @Size(min = 3, max = 3, message = "must be a 3 letter currency code") String currency,
        @Valid MethodSpec method,
        Map<String, Object> destination,
        @Size(min = 2, max = 2, message = "must be a 2 letter country code") String country) {
}
