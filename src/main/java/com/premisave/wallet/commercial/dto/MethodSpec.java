package com.premisave.wallet.commercial.dto;

import com.premisave.wallet.commercial.enums.MethodType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record MethodSpec(
        @NotNull MethodType type,
        @Size(max = 30) String provider,
        @Size(max = 500) String returnUrl,
        @Size(max = 500) String cancelUrl,
        @Size(max = 10) String payCurrency,
        @Size(max = 20) String network,
        @Size(max = 100) String paymentMethodId) {
}
