package com.premisave.wallet.commercial.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** Every field is optional and only the fields sent change. A new planId takes effect at the next billing date. */
public record UpdateSubscriptionRequest(
        @Size(max = 64) String planId,
        @Min(1) @Max(10000) Integer quantity,
        @Size(max = 40) String paymentMethodId,
        @Size(max = 2048) String callbackUrl,
        Map<String, String> metadata) {
}
