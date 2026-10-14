package com.premisave.wallet.commercial.dto;

import com.premisave.wallet.commercial.enums.MethodType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Set;

/** Partial update. environment says which wallet the limits and settlement destinations belong to. */
public record UpdateConfigRequest(
        @Size(max = 160) String displayName,
        @Size(min = 3, max = 3) String defaultCurrency,
        Set<MethodType> enabledMethods,
        @Valid LimitsDto limits,
        @Size(max = 40) String feePlanId,
        @Valid List<SettlementDestinationDto> settlementDestinations,
        @NotNull String environment) {
}
