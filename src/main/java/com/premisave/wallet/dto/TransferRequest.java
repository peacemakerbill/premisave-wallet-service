package com.premisave.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Enhanced Transfer Request with better validation and idempotency support
 */
@Data
public class TransferRequest {

    @NotNull(message = "Amount is required")
    @Positive(message = "Amount must be greater than zero")
    private BigDecimal amount;

    @NotBlank(message = "Recipient account number (email) is required")
    private String recipientAccountNumber; // recipient's email

    private String description;

    /**
     * Optional reference for idempotency (recommended for external calls)
     * If not provided, service will generate a UUID
     */
    private String reference;

    /**
     * Optional field for future use (e.g., internal transfer code, purpose)
     */
    private String purpose;
}