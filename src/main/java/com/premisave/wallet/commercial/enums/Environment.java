package com.premisave.wallet.commercial.enums;

import java.util.Optional;

/**
 * The two environments the commercial API serves. The URL path segment is
 * the source of truth; the signed X-App-Environment header is only a
 * cross-check, and uses "production" rather than "live" for the live side.
 */
public enum Environment {
    LIVE("live", "production"),
    SANDBOX("sandbox", "sandbox");

    private final String pathSegment;
    private final String headerValue;

    Environment(String pathSegment, String headerValue) {
        this.pathSegment = pathSegment;
        this.headerValue = headerValue;
    }

    public String pathSegment() { return pathSegment; }

    public String headerValue() { return headerValue; }

    public boolean livemode() { return this == LIVE; }

    public static Optional<Environment> fromPathSegment(String segment) {
        for (Environment e : values()) {
            if (e.pathSegment.equals(segment)) return Optional.of(e);
        }
        return Optional.empty();
    }

    public static Optional<Environment> fromHeaderValue(String value) {
        if (value == null) return Optional.empty();
        for (Environment e : values()) {
            if (e.headerValue.equalsIgnoreCase(value.trim())) return Optional.of(e);
        }
        return Optional.empty();
    }
}
