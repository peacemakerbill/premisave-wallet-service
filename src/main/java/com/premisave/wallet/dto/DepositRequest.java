package com.premisave.wallet.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Enhanced Deposit Request with idempotency support
 */
@Data
public class DepositRequest {

    @NotNull(message = "Amount is required")
    @Positive(message = "Amount must be greater than zero")
    private BigDecimal amount;

    private String provider = "MPESA";

    /**
     * Optional reference for idempotency
     * Useful when retrying deposit initiation
     */
    private String reference;
}