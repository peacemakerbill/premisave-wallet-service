package com.premisave.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class TransferRequest {
    @NotNull
    @Positive
    private BigDecimal amount;

    @NotBlank
    private String recipientAccountNumber; // recipient email

    private String description;
}