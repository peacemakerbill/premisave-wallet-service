package com.premisave.wallet.commercial.dto;

import com.premisave.wallet.commercial.enums.ResultCode;
import com.premisave.wallet.commercial.enums.TxStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record SimulateRequest(
        @NotNull TxStatus outcome,
        ResultCode resultCode,
        @Min(0) @Max(3600) Integer delaySeconds) {
}
