package com.premisave.wallet.commercial.dto.billing;

import com.premisave.wallet.commercial.entity.billing.Plan;
import com.premisave.wallet.commercial.enums.MethodType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * planId is optional and generated when omitted. currency defaults to USD, intervalCount to 1, setupFee
 * to zero. allowedMethods defaults to every method that can be billed repeatedly. retryScheduleDays
 * defaults to [1, 3, 5]; an empty list means failed invoices are never retried.
 */
public record CreatePlanRequest(
        @Size(max = 64) String planId,
        @NotBlank @Size(max = 120) String name,
        @NotNull BigDecimal amount,
        @Size(min = 3, max = 3, message = "must be a 3 letter currency code") String currency,
        @NotNull Plan.Interval interval,
        Integer intervalCount,
        Integer trialDays,
        BigDecimal setupFee,
        Set<MethodType> allowedMethods,
        List<Integer> retryScheduleDays,
        Plan.OnExhausted onExhausted,
        Map<String, String> metadata) {
}
