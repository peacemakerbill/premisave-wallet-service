package com.premisave.wallet.commercial.onboarding;

import java.util.Locale;
import java.util.regex.Pattern;

/** The PRM merchant number format: PRM- followed by ten digits, counted up from 1. */
public final class MerchantNumbers {

    public static final String PREFIX = "PRM-";
    public static final long MAX = 9_999_999_999L;
    private static final Pattern REAL = Pattern.compile("^PRM-(\\d{10})$");

    private MerchantNumbers() {}

    public static String format(long sequence) {
        if (sequence < 1 || sequence > MAX) {
            throw new IllegalArgumentException("merchant sequence out of range: " + sequence);
        }
        return PREFIX + String.format(Locale.ROOT, "%010d", sequence);
    }

    public static boolean isReal(String number) {
        return number != null && REAL.matcher(number.trim().toUpperCase(Locale.ROOT)).matches();
    }

    /** The counter value behind a real number, or -1 when the text is not one. */
    public static long sequenceOf(String number) {
        if (number == null) return -1;
        var m = REAL.matcher(number.trim().toUpperCase(Locale.ROOT));
        return m.matches() ? Long.parseLong(m.group(1)) : -1;
    }

    public static String normalise(String number) {
        return number == null ? null : number.trim().toUpperCase(Locale.ROOT);
    }
}
