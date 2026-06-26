package com.premisave.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Enhanced Disbursement Request with idempotency support
 */
@Data
public class DisbursementRequest {

    @NotNull(message = "Amount is required")
    @Positive(message = "Amount must be greater than zero")
    private BigDecimal amount;

    @NotBlank(message = "Destination (phone number or email) is required")
    private String destination;

    private String provider = "MPESA";

    private String remarks;

    /**
     * Optional reference for idempotency (highly recommended)
     * Prevents duplicate disbursements if the request is retried
     */
    private String reference;

    /**
     * Optional field for additional context or internal tracking
     */
    private String purpose;
}