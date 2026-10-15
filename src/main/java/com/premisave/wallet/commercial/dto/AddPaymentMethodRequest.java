package com.premisave.wallet.commercial.dto;

import com.premisave.wallet.commercial.enums.MethodType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** phoneNumber is needed for MOBILE_MONEY and accountRef for PREMISAVE_WALLET. makeDefault defaults to true for a first method. */
public record AddPaymentMethodRequest(
        @NotNull MethodType type,
        @Size(max = 30) String provider,
        @Size(max = 500) String returnUrl,
        Boolean makeDefault,
        @Size(max = 20) String phoneNumber,
        @Size(max = 160) String accountRef) {
}
