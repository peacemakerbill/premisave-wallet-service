package com.premisave.wallet.commercial.rail.premisave;

import java.time.Instant;
import java.util.Locale;

/** Pure rules for wallet consents. */
public final class ConsentRules {

    /** Why a mandate cannot be used for a charge. */
    public enum Problem { NONE, NOT_A_MANDATE, NOT_APPROVED, WRONG_ACCOUNT, WRONG_MERCHANT, OVER_LIMIT }

    private ConsentRules() {}

    /** Wallet account numbers are lower case emails. */
    public static String account(String raw) {
        return raw == null ? null : raw.trim().toLowerCase(Locale.ROOT);
    }

    /** A payment consent lasts the configured time, but never beyond the payment's own expiry. */
    public static Instant paymentExpiry(Instant now, int consentMinutes, Instant paymentExpiresAt) {
        Instant byRule = now.plusSeconds(consentMinutes * 60L);
        return paymentExpiresAt != null && paymentExpiresAt.isBefore(byRule) ? paymentExpiresAt : byRule;
    }

    /** Whether a consent that is waiting for the owner has run out of time. */
    public static boolean lapsed(WalletConsent c, Instant now) {
        return c.getStatus() == WalletConsent.Status.PENDING && c.getExpiresAt() != null && !c.getExpiresAt().isAfter(now);
    }

    /** Whether the standing approval allows this merchant to take this much from this wallet. */
    public static Problem mandateProblem(WalletConsent mandate, String account, String merchantId, long cents) {
        if (mandate == null || mandate.getKind() != WalletConsent.Kind.MANDATE) return Problem.NOT_A_MANDATE;
        if (mandate.getStatus() != WalletConsent.Status.APPROVED) return Problem.NOT_APPROVED;
        if (account == null || !account.equals(mandate.getAccountNumber())) return Problem.WRONG_ACCOUNT;
        if (merchantId == null || !merchantId.equals(mandate.getMerchantId())) return Problem.WRONG_MERCHANT;
        if (cents > mandate.getAmountCents()) return Problem.OVER_LIMIT;
        return Problem.NONE;
    }

    public static String describe(Problem p) {
        return switch (p) {
            case NONE -> null;
            case NOT_A_MANDATE, WRONG_ACCOUNT, WRONG_MERCHANT -> "The wallet owner has not approved this payment method";
            case NOT_APPROVED -> "The wallet owner has not approved, or has withdrawn, permission to charge this wallet";
            case OVER_LIMIT -> "The charge is above what the wallet owner approved";
        };
    }
}
