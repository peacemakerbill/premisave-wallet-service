package com.premisave.wallet.commercial.dto;

import jakarta.validation.constraints.Size;

public record ResendRequest(@Size(max = 40) String registrationId, @Size(max = 2048) String callbackUrl) {
}
