package com.premisave.wallet.commercial.dto.link;

import com.premisave.wallet.commercial.dto.payment.MethodSpec;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** merchantReference becomes the part after the prefix, so it is limited to letters, digits and hyphens. */
public record CreateAccountReferenceRequest(
        @NotBlank @Size(max = 20) String merchantReference,
        BigDecimal expectedAmount,
        @Size(min = 3, max = 3, message = "must be a 3 letter currency code") String currency,
        @NotNull @Valid MethodSpec method,
        @Size(max = 40) String expiresAt) {
}
