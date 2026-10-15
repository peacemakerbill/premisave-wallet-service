package com.premisave.wallet.commercial.beneficiary;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Compares the name a merchant expects with the name a provider has on record.
 * Order of words does not matter, case and accents are ignored, and the masked or short forms
 * providers return are understood: "JOHN D***" and "J DOE" both match "JOHN DOE" as PARTIAL.
 * A match on one word out of several is not enough, because a payout must not go to a
 * stranger who happens to share a first name.
 */
public final class NameMatcher {

    public enum Match {
        EXACT, PARTIAL, MISMATCH, NOT_REQUESTED, UNAVAILABLE;

        /** The results that let a payout with a required beneficiary check go ahead. */
        public boolean passes() {
            return this == EXACT || this == PARTIAL;
        }
    }

    private NameMatcher() {}

    public static Match compare(String expected, String registered) {
        List<String> e = tokens(expected);
        if (e.isEmpty()) return Match.NOT_REQUESTED;
        List<String> r = tokens(registered);
        if (r.isEmpty()) return Match.UNAVAILABLE;

        boolean[] eUsed = new boolean[e.size()];
        boolean[] rUsed = new boolean[r.size()];
        int exact = pair(e, r, eUsed, rUsed, 0);
        int loose = exact + pair(e, r, eUsed, rUsed, 1) + pair(e, r, eUsed, rUsed, 2);

        if (exact == e.size() && exact == r.size()) return Match.EXACT;
        int shorter = Math.min(e.size(), r.size());
        if (loose == shorter && (shorter >= 2 || e.size() == r.size())) return Match.PARTIAL;
        return Match.MISMATCH;
    }

    /** Pairs unused words. level 0: identical, 1: one is a masked or initial form of the other, 2: one typo. */
    private static int pair(List<String> e, List<String> r, boolean[] eUsed, boolean[] rUsed, int level) {
        int made = 0;
        for (int i = 0; i < e.size(); i++) {
            if (eUsed[i]) continue;
            for (int j = 0; j < r.size(); j++) {
                if (rUsed[j]) continue;
                if (matches(e.get(i), r.get(j), level)) {
                    eUsed[i] = true;
                    rUsed[j] = true;
                    made++;
                    break;
                }
            }
        }
        return made;
    }

    private static boolean matches(String a, String b, int level) {
        return switch (level) {
            case 0 -> a.equals(b) && !a.contains("*");
            case 1 -> prefixForm(a, b) || prefixForm(b, a);
            default -> a.length() >= 5 && b.length() >= 5 && !a.contains("*") && !b.contains("*") && withinOneEdit(a, b);
        };
    }

    /** True when masked is a short form of full: "D***" or "D" for "DOE". */
    private static boolean prefixForm(String masked, String full) {
        if (full.contains("*")) return false;
        String prefix = masked.endsWith("*") ? masked.replace("*", "") : (masked.length() == 1 ? masked : null);
        return prefix != null && !prefix.isEmpty() && full.startsWith(prefix);
    }

    static boolean withinOneEdit(String a, String b) {
        int la = a.length();
        int lb = b.length();
        if (Math.abs(la - lb) > 1) return false;
        int i = 0;
        int j = 0;
        int edits = 0;
        while (i < la && j < lb) {
            if (a.charAt(i) == b.charAt(j)) {
                i++;
                j++;
                continue;
            }
            if (++edits > 1) return false;
            if (la > lb) i++;
            else if (lb > la) j++;
            else {
                i++;
                j++;
            }
        }
        return edits + (la - i) + (lb - j) <= 1;
    }

    static List<String> tokens(String name) {
        List<String> out = new ArrayList<>();
        if (name == null) return out;
        String s = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toUpperCase(Locale.ROOT);
        for (String t : s.replaceAll("[^A-Z0-9*]+", " ").trim().split(" ")) {
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
