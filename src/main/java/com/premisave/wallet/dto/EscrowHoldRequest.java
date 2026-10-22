package com.premisave.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Body for POST /internal/escrow/hold. amount is the booking price P, not P plus commission: the commission is
 * worked out here and charged on top.
 */
@Data
public class EscrowHoldRequest {

    @NotBlank(message = "senderUserId is required")
    private String senderUserId;

    @NotBlank(message = "beneficiaryEmail is required")
    private String beneficiaryEmail;

    @NotNull(message = "Amount is required")
    @Positive(message = "Amount must be greater than zero")
    private BigDecimal amount;

    private String description;

    @NotBlank(message = "reference is required, it is the idempotency key")
    private String reference;

    @NotBlank(message = "initiatedBy is required, identify which service is calling this endpoint")
    private String initiatedBy;
}
