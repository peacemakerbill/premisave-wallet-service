package com.premisave.wallet.commercial.onboarding;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** field is a form field name such as taxPin, or document:TAX_COMPLIANCE for a document. */
public record ReturnItemRequest(@NotBlank @Size(max = 80) String field, @NotBlank @Size(max = 1000) String message) {
}
