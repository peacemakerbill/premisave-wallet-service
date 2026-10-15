package com.premisave.wallet.commercial.dto;

import jakarta.validation.constraints.Size;

/** resumeAt is an ISO 8601 UTC time; without it the subscription stays paused until it is resumed. */
public record PauseSubscriptionRequest(
        @Size(max = 40) String resumeAt,
        @Size(max = 200) String reason) {
}
