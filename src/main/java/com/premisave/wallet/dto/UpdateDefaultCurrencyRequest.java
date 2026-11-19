package com.premisave.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class UpdateDefaultCurrencyRequest {

    /** A three letter currency code such as USD, KES or EUR. */
    @NotBlank
    @Pattern(regexp = "^[A-Za-z]{3}$", message = "Use a three letter currency code, for example USD")
    private String currency;
}
