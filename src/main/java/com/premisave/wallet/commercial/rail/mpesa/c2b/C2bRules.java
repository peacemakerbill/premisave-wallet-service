package com.premisave.wallet.commercial.rail.mpesa.c2b;

import com.premisave.wallet.commercial.link.AccountReference;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

/** Pure rules for matching a paybill payment to an account reference. */
public final class C2bRules {

    /** Something wrong with a payment that is worth a person's attention but does not stop it being credited. */
    public enum Flag { NONE, AMOUNT_MISMATCH, REFERENCE_EXPIRED, REFERENCE_CLOSED }

    /** The answer to Safaricom's validation request. The codes are Daraja's own (C2B00011 to C2B00016). */
    public record Verdict(boolean accept, String code, String reason) {
        static Verdict yes() { return new Verdict(true, "0", "Accepted"); }
        static Verdict no(String code, String reason) { return new Verdict(false, code, reason); }
    }

    public static final String INVALID_ACCOUNT = "C2B00012";
    public static final String INVALID_AMOUNT = "C2B00013";
    public static final String INVALID_SHORTCODE = "C2B00015";
    public static final String OTHER_ERROR = "C2B00016";

    private static final Pattern REFERENCE = Pattern.compile("PS\\d{3,}-[A-Z0-9-]{1,20}");

    private C2bRules() {}

    /** What customers type is trimmed, upper cased and stripped of spaces, so "ps1234 - inv001" finds PS1234-INV001. */
    public static String normaliseReference(String raw) {
        return raw == null ? "" : raw.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    /** True when an account number has the shape of a commercial reference; used to route a shared paybill. */
    public static boolean looksLikeReference(String normalised) {
        return normalised != null && REFERENCE.matcher(normalised).matches();
    }

    /** A reference that can receive money at all: a live mobile money reference for this paybill. */
    public static boolean usable(AccountReference ref, String shortcode) {
        return ref != null && ref.isLivemode() && "MOBILE_MONEY".equals(ref.getMethodType())
                && shortcode != null && shortcode.equals(ref.getShortcode());
    }

    /** NONE while the reference is open and in date. */
    public static Flag state(AccountReference ref, Instant now) {
        if (!"OPEN".equals(ref.getStatus())) return Flag.REFERENCE_CLOSED;
        if (ref.getExpiresAt() != null && !ref.getExpiresAt().isAfter(now)) return Flag.REFERENCE_EXPIRED;
        return Flag.NONE;
    }

    /** A reference in KES must be paid exactly; one in another currency within a small tolerance of its USD value. */
    public static boolean exactAmount(BigDecimal expected, BigDecimal paid) {
        return expected != null && paid != null
                && expected.setScale(2, RoundingMode.HALF_UP).compareTo(paid.setScale(2, RoundingMode.HALF_UP)) == 0;
    }

    /** Two USD amounts agree when they differ by no more than 1 percent or 2 cents, whichever is larger. */
    public static boolean usdWithinTolerance(long expectedCents, long paidCents) {
        long tolerance = Math.max(2, expectedCents / 100);
        return Math.abs(expectedCents - paidCents) <= tolerance;
    }

    /** The answer to validation: refuse what cannot be credited properly, because no money has moved yet. */
    public static Verdict validation(AccountReference ref, String shortcode, Instant now, boolean amountOk) {
        if (!usable(ref, shortcode)) return Verdict.no(INVALID_ACCOUNT, "Invalid account number");
        Flag state = state(ref, now);
        if (state != Flag.NONE) return Verdict.no(INVALID_ACCOUNT, state == Flag.REFERENCE_EXPIRED ? "This account number has expired" : "This account number is closed");
        if (!amountOk) return Verdict.no(INVALID_AMOUNT, "Invalid amount");
        return Verdict.yes();
    }

    /** What to flag on a payment that has already been paid: it is credited whatever is wrong. */
    public static Flag confirmationFlag(AccountReference ref, Instant now, boolean amountOk) {
        Flag state = state(ref, now);
        if (state != Flag.NONE) return state;
        return amountOk ? Flag.NONE : Flag.AMOUNT_MISMATCH;
    }
}
