package com.premisave.wallet.commercial.dto;

import jakarta.validation.constraints.Size;

/** atPeriodEnd defaults to false, which cancels at once. */
public record CancelSubscriptionRequest(
        Boolean atPeriodEnd,
        @Size(max = 200) String reason) {
}
