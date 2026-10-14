package com.premisave.wallet.commercial.util;

import java.util.Collection;
import java.util.regex.Pattern;

/** Matches event types such as payment.succeeded against subscriptions such as payment.* or *. */
public final class EventTypeMatcher {

    private static final Pattern VALID_PATTERN = Pattern.compile("^(\\*|[a-z_]+(\\.[a-z_]+)*(\\.\\*)?)$");

    private EventTypeMatcher() {}

    public static boolean isValidPattern(String pattern) {
        return pattern != null && VALID_PATTERN.matcher(pattern).matches();
    }

    public static boolean matches(String pattern, String eventType) {
        if (pattern.equals("*")) return true;
        if (pattern.endsWith(".*")) {
            return eventType.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return pattern.equals(eventType);
    }

    public static boolean matchesAny(Collection<String> patterns, String eventType) {
        if (patterns == null) return false;
        for (String p : patterns) {
            if (matches(p, eventType)) return true;
        }
        return false;
    }
}
