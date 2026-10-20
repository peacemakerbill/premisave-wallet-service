package com.premisave.wallet.commercial.dto.merchant;

import com.premisave.wallet.commercial.enums.MerchantStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ChangeStatusRequest(@NotNull String environment, @NotNull MerchantStatus status, @Size(max = 300) String reason) {
}
