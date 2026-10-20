package com.premisave.wallet.commercial.dto.settlement;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateSettlementRequest(
        @NotNull @DecimalMin(value = "0.01", message = "must be at least 0.01") BigDecimal amount,
        @Size(min = 3, max = 3, message = "must be a 3 letter currency code") String currency,
        @NotBlank @Size(max = 40) String destinationId,
        @NotBlank @Size(max = 64) String merchantReference) {
}
