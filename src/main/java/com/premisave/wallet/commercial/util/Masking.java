package com.premisave.wallet.commercial.util;

/** Masks personal and account data in anything returned to the merchant in lists or events. */
public final class Masking {

    private Masking() {}

    /** 254712345678 becomes 2547****5678. Short values keep only the last two characters. */
    public static String phone(String phone) {
        if (phone == null) return null;
        int n = phone.length();
        if (n >= 10) return phone.substring(0, 4) + "*".repeat(n - 8) + phone.substring(n - 4);
        return n <= 2 ? phone : "*".repeat(n - 2) + phone.substring(n - 2);
    }

    /** jane@example.com becomes j***@example.com. */
    public static String email(String email) {
        if (email == null) return null;
        int at = email.indexOf('@');
        if (at <= 1) return email;
        return email.charAt(0) + "***" + email.substring(at);
    }

    /** Keeps the first four and last two characters of an account number. */
    public static String account(String value) {
        if (value == null) return null;
        int n = value.length();
        if (n <= 6) return "*".repeat(Math.max(0, n - 2)) + value.substring(Math.max(0, n - 2));
        return value.substring(0, 4) + "*".repeat(n - 6) + value.substring(n - 2);
    }

    /** Keeps the first and last four characters of a crypto address. */
    public static String address(String value) {
        if (value == null || value.length() <= 10) return value;
        return value.substring(0, 4) + "..." + value.substring(value.length() - 4);
    }
}
