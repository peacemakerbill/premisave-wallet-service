package com.premisave.wallet.commercial.onboarding;

import jakarta.validation.constraints.Size;

/** Message shown to the applicant with an approval, rejection or reopening. Required for rejection and reopening. */
public record DecisionRequest(@Size(max = 2000) String message) {
}
