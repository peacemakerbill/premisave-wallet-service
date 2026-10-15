package com.premisave.wallet.commercial.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * paymentMethodId defaults to the customer's default method. startAt is an ISO 8601 UTC time, now when
 * omitted. trialDays overrides the plan's trial (0 turns it off).
 */
public record CreateSubscriptionRequest(
        @NotBlank @Size(max = 40) String customerId,
        @NotBlank @Size(max = 64) String planId,
        @Size(max = 40) String paymentMethodId,
        @Min(1) @Max(10000) Integer quantity,
        @Size(max = 40) String startAt,
        @Min(0) @Max(365) Integer trialDays,
        @Size(max = 2048) String callbackUrl,
        Map<String, String> metadata) {
}
