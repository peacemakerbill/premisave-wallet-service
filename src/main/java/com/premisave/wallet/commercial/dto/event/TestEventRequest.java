package com.premisave.wallet.commercial.dto.event;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TestEventRequest(@NotBlank @Size(max = 60) String eventType, @Size(max = 40) String registrationId) {
}
