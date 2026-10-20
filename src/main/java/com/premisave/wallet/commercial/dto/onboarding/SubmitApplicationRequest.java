package com.premisave.wallet.commercial.dto.onboarding;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.util.List;

public record SubmitApplicationRequest(@Size(max = 2000) String message, @Valid List<ItemResponse> responses) {
}
