package com.premisave.wallet.commercial.dto.payment;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Map;

/** to is {"type": "PREMISAVE_WALLET", "accountRef": "recipient@example.com"}. */
public record CreateTransferRequest(
        @NotNull @DecimalMin(value = "0.01", message = "must be at least 0.01") BigDecimal amount,
        @Size(min = 3, max = 3, message = "must be a 3 letter currency code") String currency,
        @NotNull Map<String, Object> to,
        @NotBlank @Size(max = 64) String merchantReference,
        @Size(max = 100) String note) {
}
