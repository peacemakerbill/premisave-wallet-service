package com.premisave.wallet.commercial.service.billing;

import com.premisave.wallet.commercial.entity.billing.Plan;
import com.premisave.wallet.commercial.enums.MethodType;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.util.Money;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Input checks for plans, kept apart from the service so they can be tested alone. */
public final class PlanRules {

    public static final Pattern PLAN_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._\\-]{2,63}$");
    public static final List<Integer> DEFAULT_RETRY_DAYS = List.of(1, 3, 5);
    public static final int MAX_RETRIES = 6;
    public static final int MAX_RETRY_DAY = 30;
    public static final int MAX_TRIAL_DAYS = 365;

    private PlanRules() {}

    public static String planId(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String id = raw.trim();
        if (!PLAN_ID.matcher(id).matches()) {
            throw CommercialException.validation("planId", "must be 3 to 64 letters, digits, dots, dashes or underscores");
        }
        return id;
    }

    public static String currency(String raw) {
        if (raw == null || raw.isBlank()) return Money.BASE_CURRENCY;
        String c = raw.trim().toUpperCase(Locale.ROOT);
        if (!c.matches("[A-Z]{3}")) throw CommercialException.validation("currency", "must be a 3 letter currency code");
        return c;
    }

    public static BigDecimal price(String field, BigDecimal amount, boolean allowZero) {
        if (amount == null) throw CommercialException.validation(field, "is required");
        if (Money.hasMoreThanCents(amount)) throw CommercialException.validation(field, "must have at most 2 decimal places");
        if (amount.signum() < 0 || (!allowZero && amount.signum() == 0)) {
            throw CommercialException.validation(field, allowZero ? "must not be negative" : "must be greater than zero");
        }
        return amount.setScale(2, java.math.RoundingMode.UNNECESSARY);
    }

    public static int intervalCount(Plan.Interval interval, Integer count) {
        int c = count == null ? 1 : count;
        int max = switch (interval) {
            case DAY -> 365;
            case WEEK -> 52;
            case MONTH -> 36;
            case YEAR -> 5;
        };
        if (c < 1 || c > max) {
            throw CommercialException.validation("intervalCount", "must be between 1 and " + max + " for " + interval);
        }
        return c;
    }

    public static int trialDays(Integer days) {
        int d = days == null ? 0 : days;
        if (d < 0 || d > MAX_TRIAL_DAYS) throw CommercialException.validation("trialDays", "must be between 0 and " + MAX_TRIAL_DAYS);
        return d;
    }

    /** Null means the default schedule; an empty list means never retry. Days must be increasing. */
    public static List<Integer> retrySchedule(List<Integer> raw) {
        if (raw == null) return new ArrayList<>(DEFAULT_RETRY_DAYS);
        if (raw.size() > MAX_RETRIES) throw CommercialException.validation("retryScheduleDays", "can have at most " + MAX_RETRIES + " entries");
        int previous = 0;
        for (Integer d : raw) {
            if (d == null || d < 1 || d > MAX_RETRY_DAY) {
                throw CommercialException.validation("retryScheduleDays", "each entry must be a number of days from 1 to " + MAX_RETRY_DAY);
            }
            if (d <= previous) throw CommercialException.validation("retryScheduleDays", "entries must be in increasing order");
            previous = d;
        }
        return new ArrayList<>(raw);
    }

    /** Null or empty allows every method that can be billed repeatedly. */
    public static Set<MethodType> methods(Set<MethodType> raw) {
        if (raw == null || raw.isEmpty()) return EnumSet.copyOf(Plan.RECURRING_METHODS);
        for (MethodType m : raw) {
            if (!Plan.RECURRING_METHODS.contains(m)) {
                throw CommercialException.validation("allowedMethods", m + " cannot be billed repeatedly; use CARD, PAYPAL, PREMISAVE_WALLET or MOBILE_MONEY");
            }
        }
        return EnumSet.copyOf(raw);
    }
}
