package com.premisave.wallet.commercial.dto;

import com.premisave.wallet.commercial.enums.MethodType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SettlementDestinationDto(
        @Size(max = 40) String id,
        @NotNull MethodType type,
        @Size(max = 80) String label,
        @Size(min = 2, max = 2) String country,
        @Size(max = 20) String phoneNumber,
        @Size(max = 40) String accountNumber,
        @Size(max = 20) String bankCode,
        @Size(max = 160) String email) {
}
