package com.premisave.wallet.commercial.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Sandbox helper 13.3. amount is in USD and defaults to the whole payment. */
public record CreateTestDisputeRequest(
        @NotBlank @Size(max = 64) String paymentId,
        @DecimalMin(value = "0.01", message = "must be at least 0.01") BigDecimal amount,
        @Size(max = 60) String reason) {
}
