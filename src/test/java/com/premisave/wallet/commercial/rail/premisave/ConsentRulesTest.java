package com.premisave.wallet.commercial.rail.premisave;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsentRulesTest {

    private static final Instant NOW = Instant.parse("2026-10-16T10:00:00Z");

    private static WalletConsent mandate(WalletConsent.Status status) {
        WalletConsent m = new WalletConsent();
        m.setKind(WalletConsent.Kind.MANDATE);
        m.setStatus(status);
        m.setAccountNumber("ama@example.com");
        m.setMerchantId("mer_1");
        m.setAmountCents(50_000);
        return m;
    }

    @Test
    void accountsAreLowerCaseEmails() {
        assertEquals("ama@example.com", ConsentRules.account("  Ama@Example.COM "));
        assertNull(ConsentRules.account(null));
    }

    @Test
    void paymentConsentLastsTheConfiguredTimeButNeverPastThePayment() {
        assertEquals(NOW.plusSeconds(900), ConsentRules.paymentExpiry(NOW, 15, null));
        assertEquals(NOW.plusSeconds(900), ConsentRules.paymentExpiry(NOW, 15, NOW.plusSeconds(3600)));
        assertEquals(NOW.plusSeconds(300), ConsentRules.paymentExpiry(NOW, 15, NOW.plusSeconds(300)));
    }

    @Test
    void onlyAWaitingConsentCanLapse() {
        WalletConsent c = mandate(WalletConsent.Status.PENDING);
        c.setExpiresAt(NOW.minusSeconds(1));
        assertTrue(ConsentRules.lapsed(c, NOW));
        c.setExpiresAt(NOW.plusSeconds(1));
        assertFalse(ConsentRules.lapsed(c, NOW));
        c.setStatus(WalletConsent.Status.APPROVED);
        c.setExpiresAt(NOW.minusSeconds(1));
        assertFalse(ConsentRules.lapsed(c, NOW));
    }

    @Test
    void anApprovedMandateAllowsAChargeWithinItsLimit() {
        assertEquals(ConsentRules.Problem.NONE,
                ConsentRules.mandateProblem(mandate(WalletConsent.Status.APPROVED), "ama@example.com", "mer_1", 50_000));
        assertNull(ConsentRules.describe(ConsentRules.Problem.NONE));
    }

    @Test
    void aMandateRefusesAnythingOutsideWhatWasApproved() {
        WalletConsent ok = mandate(WalletConsent.Status.APPROVED);
        assertEquals(ConsentRules.Problem.OVER_LIMIT, ConsentRules.mandateProblem(ok, "ama@example.com", "mer_1", 50_001));
        assertEquals(ConsentRules.Problem.WRONG_MERCHANT, ConsentRules.mandateProblem(ok, "ama@example.com", "mer_2", 100));
        assertEquals(ConsentRules.Problem.WRONG_ACCOUNT, ConsentRules.mandateProblem(ok, "kofi@example.com", "mer_1", 100));
        assertEquals(ConsentRules.Problem.NOT_APPROVED,
                ConsentRules.mandateProblem(mandate(WalletConsent.Status.REVOKED), "ama@example.com", "mer_1", 100));
        assertEquals(ConsentRules.Problem.NOT_APPROVED,
                ConsentRules.mandateProblem(mandate(WalletConsent.Status.PENDING), "ama@example.com", "mer_1", 100));
        assertEquals(ConsentRules.Problem.NOT_A_MANDATE, ConsentRules.mandateProblem(null, "ama@example.com", "mer_1", 100));
        WalletConsent payment = mandate(WalletConsent.Status.APPROVED);
        payment.setKind(WalletConsent.Kind.PAYMENT);
        assertEquals(ConsentRules.Problem.NOT_A_MANDATE, ConsentRules.mandateProblem(payment, "ama@example.com", "mer_1", 100));
    }

    @Test
    void everyProblemHasAMessageThatRevealsNothingElse() {
        for (ConsentRules.Problem p : ConsentRules.Problem.values()) {
            if (p != ConsentRules.Problem.NONE) assertFalse(ConsentRules.describe(p).isBlank());
        }
    }

    @Test
    void closedStatuses() {
        assertTrue(WalletConsent.Status.DECLINED.isClosed());
        assertTrue(WalletConsent.Status.REVERSED.isClosed());
        assertFalse(WalletConsent.Status.PENDING.isClosed());
        assertFalse(WalletConsent.Status.APPROVED.isClosed());
        assertFalse(WalletConsent.Status.USED.isClosed());
    }
}
