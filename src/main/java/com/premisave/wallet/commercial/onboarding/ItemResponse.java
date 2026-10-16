package com.premisave.wallet.commercial.onboarding;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The applicant's answer to one returned item when resubmitting. */
public record ItemResponse(@NotBlank String itemId, @Size(max = 1000) String response) {
}
