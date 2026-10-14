package com.premisave.wallet.commercial.dto;

import com.premisave.wallet.commercial.enums.AppStatus;
import com.premisave.wallet.commercial.enums.MethodType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record UpdateAppRequest(
        @Size(max = 120) String name,
        AppStatus status,
        Set<MethodType> enabledMethods,
        @Valid LimitsDto limits) {
}
