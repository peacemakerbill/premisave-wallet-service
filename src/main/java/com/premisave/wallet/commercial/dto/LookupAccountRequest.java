package com.premisave.wallet.commercial.dto;

import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record LookupAccountRequest(@NotNull Map<String, Object> destination) {
}
