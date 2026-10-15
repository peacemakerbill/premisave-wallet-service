package com.premisave.wallet.commercial.billing;

import com.premisave.wallet.commercial.enums.MethodType;
import com.premisave.wallet.commercial.exception.CommercialException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanRulesTest {

    @Test
    void planIdIsOptionalButChecked() {
        assertNull(PlanRules.planId(null));
        assertNull(PlanRules.planId("  "));
        assertEquals("plan_pro_monthly", PlanRules.planId(" plan_pro_monthly "));
        assertThrows(CommercialException.class, () -> PlanRules.planId("a b"));
        assertThrows(CommercialException.class, () -> PlanRules.planId("ab"));
    }

    @Test
    void currencyDefaultsToUsdAndIsUppercased() {
        assertEquals("USD", PlanRules.currency(null));
        assertEquals("KES", PlanRules.currency("kes"));
        assertThrows(CommercialException.class, () -> PlanRules.currency("US"));
    }

    @Test
    void priceRules() {
        assertEquals("10.00", PlanRules.price("amount", new BigDecimal("10"), false).toPlainString());
        assertThrows(CommercialException.class, () -> PlanRules.price("amount", BigDecimal.ZERO, false));
        assertThrows(CommercialException.class, () -> PlanRules.price("amount", new BigDecimal("10.001"), false));
        assertThrows(CommercialException.class, () -> PlanRules.price("amount", null, false));
        assertEquals("0.00", PlanRules.price("setupFee", BigDecimal.ZERO, true).toPlainString());
        assertThrows(CommercialException.class, () -> PlanRules.price("setupFee", new BigDecimal("-1"), true));
    }

    @Test
    void intervalCountIsBoundedPerInterval() {
        assertEquals(1, PlanRules.intervalCount(Plan.Interval.MONTH, null));
        assertEquals(3, PlanRules.intervalCount(Plan.Interval.MONTH, 3));
        assertThrows(CommercialException.class, () -> PlanRules.intervalCount(Plan.Interval.YEAR, 6));
        assertThrows(CommercialException.class, () -> PlanRules.intervalCount(Plan.Interval.DAY, 0));
    }

    @Test
    void retryScheduleRules() {
        assertEquals(List.of(1, 3, 5), PlanRules.retrySchedule(null));
        assertEquals(List.of(), PlanRules.retrySchedule(List.of()));
        assertEquals(List.of(2, 7), PlanRules.retrySchedule(List.of(2, 7)));
        assertThrows(CommercialException.class, () -> PlanRules.retrySchedule(List.of(3, 3)));
        assertThrows(CommercialException.class, () -> PlanRules.retrySchedule(List.of(5, 1)));
        assertThrows(CommercialException.class, () -> PlanRules.retrySchedule(List.of(0)));
        assertThrows(CommercialException.class, () -> PlanRules.retrySchedule(List.of(31)));
        assertThrows(CommercialException.class, () -> PlanRules.retrySchedule(List.of(1, 2, 3, 4, 5, 6, 7)));
    }

    @Test
    void trialDaysAreBounded() {
        assertEquals(0, PlanRules.trialDays(null));
        assertEquals(14, PlanRules.trialDays(14));
        assertThrows(CommercialException.class, () -> PlanRules.trialDays(-1));
        assertThrows(CommercialException.class, () -> PlanRules.trialDays(366));
    }

    @Test
    void onlyRepeatableMethodsAreAllowed() {
        Set<MethodType> all = PlanRules.methods(null);
        assertTrue(all.containsAll(Plan.RECURRING_METHODS));
        assertEquals(EnumSet.of(MethodType.CARD), PlanRules.methods(EnumSet.of(MethodType.CARD)));
        assertThrows(CommercialException.class, () -> PlanRules.methods(EnumSet.of(MethodType.CRYPTO)));
        assertThrows(CommercialException.class, () -> PlanRules.methods(EnumSet.of(MethodType.BANK_TRANSFER, MethodType.CARD)));
    }
}
