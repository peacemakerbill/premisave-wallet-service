package com.premisave.wallet.commercial.util;

import java.security.SecureRandom;

/** Prefixed random identifiers such as pay_k3m9x0q2t7ab. 60 bits of randomness per id. */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz".toCharArray();
    private static final int LENGTH = 12;

    private Ids() {}

    public static String next(String prefix) {
        char[] chars = new char[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            chars[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return prefix + "_" + new String(chars);
    }

    public static String requestId() {
        return next("req");
    }
}
