package com.premisave.wallet.commercial.dto.onboarding;

import com.premisave.wallet.commercial.enums.onboarding.DocumentVerdict;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record DocumentVerdictRequest(@NotNull DocumentVerdict verdict, @Size(max = 1000) String note) {
}
