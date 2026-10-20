package com.premisave.wallet.commercial.dto.payment;

import jakarta.validation.constraints.Size;

/** The payer of a collection. Which fields are needed depends on the method. */
public record CustomerInfo(
        @Size(max = 120) String name,
        @Size(max = 160) String email,
        @Size(max = 20) String phone,
        @Size(max = 160) String accountRef) {
}
