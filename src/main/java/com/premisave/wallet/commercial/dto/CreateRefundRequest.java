package com.premisave.wallet.commercial.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Map;

/** amount is optional (everything still refundable); when given, currency says whether it is USD or the payment's own currency. */
public record CreateRefundRequest(
        @NotBlank @Size(max = 64) String paymentId,
        @DecimalMin(value = "0.01", message = "must be at least 0.01") BigDecimal amount,
        @Size(min = 3, max = 3, message = "must be a 3 letter currency code") String currency,
        @Size(max = 200) String reason,
        @Size(max = 64) String merchantReference,
        @Size(max = 2048) String callbackUrl,
        Map<String, String> metadata) {
}
