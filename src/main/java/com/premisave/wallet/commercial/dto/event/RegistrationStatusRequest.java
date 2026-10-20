package com.premisave.wallet.commercial.dto.event;

import com.premisave.wallet.commercial.enums.RegistrationStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RegistrationStatusRequest(@NotNull RegistrationStatus status, @Size(max = 300) String reason) {
}
