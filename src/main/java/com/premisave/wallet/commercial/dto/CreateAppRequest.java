package com.premisave.wallet.commercial.dto;

import com.premisave.wallet.commercial.enums.MethodType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record CreateAppRequest(
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9_\\-]{3,64}$", message = "must be 3 to 64 letters, digits, _ or -") String appId,
        @NotBlank String environment,
        @NotBlank @Size(max = 120) String name,
        Set<MethodType> enabledMethods,
        @Valid LimitsDto limits) {
}
