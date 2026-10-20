package com.premisave.wallet.commercial.dto.sandbox;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** to is an ISO 8601 UTC time, later than the subscription's current clock and at most one year ahead. */
public record AdvanceClockRequest(@NotBlank @Size(max = 40) String to) {
}
