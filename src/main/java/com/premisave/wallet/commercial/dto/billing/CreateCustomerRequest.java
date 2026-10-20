package com.premisave.wallet.commercial.dto.billing;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** name is required; email, phone and merchantReference are optional. phone is a Kenyan number or full MSISDN. */
public record CreateCustomerRequest(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 160) String email,
        @Size(max = 20) String phone,
        @Size(max = 64) String merchantReference,
        Map<String, String> metadata) {
}
