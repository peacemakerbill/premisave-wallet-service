package com.premisave.wallet.commercial.dto;

import com.premisave.wallet.commercial.enums.Direction;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** direction defaults to PAYOUT. */
public record VerifyBeneficiaryRequest(
        @NotNull Map<String, Object> destination,
        @Size(max = 140) String expectedName,
        Direction direction) {
}
