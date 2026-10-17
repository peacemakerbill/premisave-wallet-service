package com.premisave.wallet.commercial.dto;

import com.premisave.wallet.commercial.enums.AppStatus;
import com.premisave.wallet.commercial.enums.MethodType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Set;

/** Sent by the developer service whenever an app changes, so scheduled work sees it without waiting for a request. */
public record AppContextRequest(
        @NotBlank String merchantId,
        @NotBlank String environment,
        @NotNull AppStatus status,
        Set<MethodType> enabledMethods,
        @Valid LimitsDto limits,
        @NotNull Long version) {
}
