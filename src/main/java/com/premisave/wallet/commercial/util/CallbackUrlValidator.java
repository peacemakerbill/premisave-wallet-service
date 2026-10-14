package com.premisave.wallet.commercial.util;

import com.premisave.wallet.commercial.enums.Environment;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Registration time checks on merchant supplied callback URLs. These catch
 * obvious mistakes and obviously unsafe targets. The delivery component
 * (developer service) must still resolve DNS and block private ranges at
 * connect time, because a hostname can point anywhere.
 */
public final class CallbackUrlValidator {

    public static final int MAX_LENGTH = 2048;
    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");

    private CallbackUrlValidator() {}

    /** Returns the reason a URL is not acceptable, or empty when it is. */
    public static Optional<String> check(String url, Environment env) {
        if (url == null || url.isBlank()) return Optional.of("url is required");
        if (url.length() > MAX_LENGTH) return Optional.of("url is longer than " + MAX_LENGTH + " characters");

        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return Optional.of("url is not a valid URI");
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (host == null || host.isBlank()) return Optional.of("url must include a host");
        if (uri.getUserInfo() != null) return Optional.of("url must not contain credentials");
        if (uri.getFragment() != null) return Optional.of("url must not contain a fragment");

        if (env == Environment.LIVE) {
            if (!scheme.equals("https")) return Optional.of("live callback urls must use https");
            String h = host.toLowerCase(Locale.ROOT);
            if (h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal")) {
                return Optional.of("live callback urls must use a public host");
            }
            if (h.startsWith("[") || h.contains(":")) {
                return Optional.of("live callback urls must use a hostname, not an IPv6 literal");
            }
            var m = IPV4.matcher(h);
            if (m.matches() && isNonPublicIpv4(
                    Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)))) {
                return Optional.of("live callback urls must not point at a private or reserved address");
            }
            return Optional.empty();
        }

        if (!scheme.equals("https") && !scheme.equals("http")) {
            return Optional.of("url must use http or https");
        }
        return Optional.empty();
    }

    private static boolean isNonPublicIpv4(int a, int b) {
        return a == 0 || a == 10 || a == 127
                || (a == 169 && b == 254)
                || (a == 172 && b >= 16 && b <= 31)
                || (a == 192 && b == 168)
                || (a == 100 && b >= 64 && b <= 127)
                || a >= 224;
    }
}
