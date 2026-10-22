package com.premisave.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Body for POST /internal/escrow/{holdReference}/payouts. recipientAccountNumber is the recipient's email (the
 * same meaning as in /internal/transfer) and must be the hold's sender or its beneficiary.
 */
@Data
public class EscrowPayoutRequest {

    @NotBlank(message = "Recipient account number (email) is required")
    private String recipientAccountNumber;

    @NotNull(message = "Amount is required")
    @Positive(message = "Amount must be greater than zero")
    private BigDecimal amount;

    private String description;

    @NotBlank(message = "reference is required, it is the idempotency key")
    private String reference;

    @NotBlank(message = "initiatedBy is required, identify which service is calling this endpoint")
    private String initiatedBy;
}
