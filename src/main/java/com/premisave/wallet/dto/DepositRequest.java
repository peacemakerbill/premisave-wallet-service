package com.premisave.wallet.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class DepositRequest {
    @NotNull
    @Positive
    private BigDecimal amount;

    private String provider; // MPESA, PAYPAL, etc.
}