package com.premisave.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class DisbursementRequest {
    @NotNull
    @Positive
    private BigDecimal amount;

    @NotBlank
    private String destination;

    private String provider = "MPESA";
    private String remarks;
}