package com.premisave.wallet.commercial.dto.sandbox;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreditRequest(
        @NotNull @DecimalMin(value = "0.01", message = "must be at least 0.01") BigDecimal amount,
        @Size(min = 3, max = 3) String currency) {
}
