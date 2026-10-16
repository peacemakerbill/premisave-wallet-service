package com.premisave.wallet.commercial.security;

import com.premisave.wallet.commercial.entity.Limits;
import com.premisave.wallet.commercial.enums.AppStatus;
import com.premisave.wallet.commercial.enums.Environment;
import com.premisave.wallet.commercial.enums.MethodType;
import com.premisave.wallet.commercial.exception.CommercialException;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * Reads the app context the developer service sends with every environment scoped call.
 *
 *   X-Merchant-Id, X-App-Id            identity (required)
 *   X-App-Status                       ACTIVE or SUSPENDED (required)
 *   X-App-Version                      the app's change counter, a whole number (required)
 *   X-App-Methods                      comma separated method names, or "none"; absent means same as the merchant
 *   X-App-Limits                       name=value pairs separated by commas; absent means no app limits
 *
 * X-App-Environment is checked against the URL by the interceptor.
 */
public final class AppContextHeaders {

    private AppContextHeaders() {}

    public static AppContext parse(Function<String, String> header, Environment env) {
        String merchantId = required(header, "X-Merchant-Id");
        String appId = required(header, "X-App-Id");
        AppStatus status;
        try {
            status = AppStatus.valueOf(required(header, "X-App-Status").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw invalid("X-App-Status", "must be ACTIVE or SUSPENDED");
        }
        long version;
        try {
            version = Long.parseLong(required(header, "X-App-Version"));
        } catch (NumberFormatException e) {
            throw invalid("X-App-Version", "must be a whole number");
        }
        return new AppContext(appId, merchantId, env, status, methods(header.apply("X-App-Methods")),
                limits(header.apply("X-App-Limits")), version);
    }

    static Set<MethodType> methods(String value) {
        if (value == null || value.isBlank()) return null;
        String v = value.trim();
        if (v.equalsIgnoreCase("none")) return EnumSet.noneOf(MethodType.class);
        Set<MethodType> set = EnumSet.noneOf(MethodType.class);
        for (String part : v.split(",")) {
            try {
                set.add(MethodType.valueOf(part.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw invalid("X-App-Methods", "'" + part.trim() + "' is not a method");
            }
        }
        return set;
    }

    static Limits limits(String value) {
        if (value == null || value.isBlank()) return null;
        Limits l = new Limits();
        for (String pair : value.split(",")) {
            String[] kv = pair.trim().split("=", 2);
            if (kv.length != 2 || kv[1].isBlank()) throw invalid("X-App-Limits", "must look like name=value,name=value");
            try {
                switch (kv[0].trim()) {
                    case "singleTransactionUsd" -> l.setSingleTransactionUsd(positive(kv[1]));
                    case "dailyVolumeUsd" -> l.setDailyVolumeUsd(positive(kv[1]));
                    case "monthlyVolumeUsd" -> l.setMonthlyVolumeUsd(positive(kv[1]));
                    case "dailyPayoutCount" -> {
                        int n = Integer.parseInt(kv[1].trim());
                        if (n < 1) throw new NumberFormatException();
                        l.setDailyPayoutCount(n);
                    }
                    default -> throw invalid("X-App-Limits", "'" + kv[0].trim() + "' is not a limit");
                }
            } catch (NumberFormatException e) {
                throw invalid("X-App-Limits", "'" + kv[0].trim() + "' must be a positive number");
            }
        }
        return l.isEmpty() ? null : l;
    }

    private static BigDecimal positive(String s) {
        BigDecimal v = new BigDecimal(s.trim());
        if (v.signum() <= 0) throw new NumberFormatException();
        return v;
    }

    private static String required(Function<String, String> header, String name) {
        String v = header.apply(name);
        if (v == null || v.isBlank()) throw invalid(name, "missing");
        return v.trim();
    }

    private static CommercialException invalid(String header, String issue) {
        return CommercialException.validation(header, issue);
    }
}
