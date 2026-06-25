package com.premisave.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class PaymentInitiateRequest {
    @NotNull
    @Positive
    private BigDecimal amount;

    @NotBlank
    private String service; // e.g., "booking"

    private String reference;
}