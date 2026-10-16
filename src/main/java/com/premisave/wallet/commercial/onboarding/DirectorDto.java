package com.premisave.wallet.commercial.onboarding;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record DirectorDto(
        @Size(max = 160) String fullName,
        @Size(max = 40) String idNumber,
        @Size(max = 60) String role,
        @Min(0) @Max(100) Integer ownershipPercent) {
}
