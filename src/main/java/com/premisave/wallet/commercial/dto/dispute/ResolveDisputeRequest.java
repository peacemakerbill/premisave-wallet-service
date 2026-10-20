package com.premisave.wallet.commercial.dto.dispute;

import jakarta.validation.constraints.NotBlank;

/** Sandbox helper: outcome is WON or LOST. */
public record ResolveDisputeRequest(@NotBlank String outcome) {
}
