package com.premisave.wallet.commercial.dto.billing;

import com.premisave.wallet.commercial.entity.billing.Plan;
import com.premisave.wallet.commercial.enums.MethodType;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every field is optional and only the fields sent change. The price fields are here only so that an
 * attempt to change them can be refused with a clear message; sending the current value is accepted.
 */
public record UpdatePlanRequest(
        @Size(max = 120) String name,
        Plan.Status status,
        Set<MethodType> allowedMethods,
        List<Integer> retryScheduleDays,
        Plan.OnExhausted onExhausted,
        Map<String, String> metadata,
        BigDecimal amount,
        @Size(max = 3) String currency,
        Plan.Interval interval,
        Integer intervalCount,
        Integer trialDays,
        BigDecimal setupFee) {
}
