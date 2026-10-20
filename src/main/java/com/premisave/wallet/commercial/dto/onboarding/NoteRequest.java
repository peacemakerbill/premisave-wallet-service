package com.premisave.wallet.commercial.dto.onboarding;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** An internal note. The applicant never sees it. */
public record NoteRequest(@NotBlank @Size(max = 2000) String note) {
}
