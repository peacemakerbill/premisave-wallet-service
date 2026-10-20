package com.premisave.wallet.commercial.dto.settlement;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** mode is MANUAL, DAILY, WEEKLY or THRESHOLD; the other fields that matter depend on it. */
public record SettlementScheduleRequest(
        @NotBlank @Size(max = 12) String mode,
        @Size(max = 40) String destinationId,
        BigDecimal minimumAmount,
        BigDecimal keepReserve,
        @Size(max = 5) String runAtUtc,
        @Size(max = 9) String dayOfWeek,
        BigDecimal thresholdAmount) {
}
