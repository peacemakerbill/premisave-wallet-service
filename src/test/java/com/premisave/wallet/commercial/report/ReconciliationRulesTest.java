package com.premisave.wallet.commercial.report;

import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.enums.LedgerEntryType;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.enums.TxType;
import com.premisave.wallet.commercial.report.ReconciliationRules.Finding;
import com.premisave.wallet.commercial.report.ReconciliationRules.Posting;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconciliationRulesTest {

    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
    private static final Instant LONG_AGO = Instant.parse("2026-10-14T08:00:00Z");

    private static CommercialTransaction tx(TxType type, TxStatus status, long amount, long fee, long settle) {
        CommercialTransaction t = new CommercialTransaction();
        t.setId("t1");
        t.setType(type);
        t.setStatus(status);
        t.setAmountCents(amount);
        t.setFeeCents(fee);
        t.setSettleCents(settle);
        t.setCreatedAt(LONG_AGO);
        t.setUpdatedAt(NOW);
        return t;
    }

    private static Posting credit(long net, long fee) {
        return new Posting(LedgerEntryType.COLLECTION_CREDIT, net, 0, fee);
    }

    private static Posting hold(long total) {
        return new Posting(LedgerEntryType.PAYOUT_HOLD, -total, total, 0);
    }

    private static Posting debit(long total, long fee) {
        return new Posting(LedgerEntryType.PAYOUT_DEBIT, 0, -total, fee);
    }

    private static Posting release(long total) {
        return new Posting(LedgerEntryType.PAYOUT_HOLD_RELEASE, total, -total, 0);
    }

    private static List<String> types(List<Finding> f) {
        return f.stream().map(Finding::type).toList();
    }

    @Test
    void matchingPaymentHasNoFindings() {
        CommercialTransaction t = tx(TxType.PAYMENT, TxStatus.SUCCESS, 1_000, 30, 970);
        assertTrue(ReconciliationRules.check(t, List.of(credit(970, 30)), NOW).isEmpty());
    }

    @Test
    void successfulPaymentWithoutCreditIsMissing() {
        assertEquals(List.of("LEDGER_MISSING"),
                types(ReconciliationRules.check(tx(TxType.PAYMENT, TxStatus.SUCCESS, 1_000, 30, 970), List.of(), NOW)));
    }

    @Test
    void creditWithTheWrongAmountIsAMismatch() {
        List<Finding> f = ReconciliationRules.check(tx(TxType.PAYMENT, TxStatus.SUCCESS, 1_000, 30, 970), List.of(credit(900, 30)), NOW);
        assertEquals(List.of("AMOUNT_MISMATCH"), types(f));
        assertEquals(900L, f.get(0).ledgerCents().longValue());
    }

    @Test
    void creditForAFailedPaymentIsFlagged() {
        assertEquals(List.of("POSTING_WITHOUT_SUCCESS"),
                types(ReconciliationRules.check(tx(TxType.PAYMENT, TxStatus.FAILED, 1_000, 30, 970), List.of(credit(970, 30)), NOW)));
    }

    @Test
    void successfulSettlementNeedsHoldAndDebit() {
        CommercialTransaction t = tx(TxType.SETTLEMENT, TxStatus.SUCCESS, 5_000, 100, 5_100);
        assertTrue(ReconciliationRules.check(t, List.of(hold(5_100), debit(5_100, 100)), NOW).isEmpty());
        assertEquals(List.of("LEDGER_MISSING"), types(ReconciliationRules.check(t, List.of(hold(5_100)), NOW)));
        assertEquals(List.of("HOLD_MISSING"), types(ReconciliationRules.check(t, List.of(debit(5_100, 100)), NOW)));
    }

    @Test
    void debitWithTheWrongFeeIsAMismatch() {
        CommercialTransaction t = tx(TxType.TRANSFER, TxStatus.SUCCESS, 5_000, 100, 5_100);
        assertEquals(List.of("AMOUNT_MISMATCH"),
                types(ReconciliationRules.check(t, List.of(hold(5_100), debit(5_100, 0)), NOW)));
    }

    @Test
    void failedPayoutMustHaveReleasedItsHold() {
        CommercialTransaction t = tx(TxType.PAYOUT, TxStatus.FAILED, 2_000, 40, 2_040);
        assertTrue(ReconciliationRules.check(t, List.of(hold(2_040), release(2_040)), NOW).isEmpty());
        assertEquals(List.of("HOLD_NOT_RELEASED"), types(ReconciliationRules.check(t, List.of(hold(2_040)), NOW)));
    }

    @Test
    void failedPayoutThatWasDebitedIsFlagged() {
        CommercialTransaction t = tx(TxType.PAYOUT, TxStatus.TIMED_OUT, 2_000, 40, 2_040);
        List<String> found = types(ReconciliationRules.check(t, List.of(hold(2_040), release(2_040), debit(2_040, 40)), NOW));
        assertEquals(List.of("POSTING_WITHOUT_SUCCESS"), found);
    }

    @Test
    void reversedPayoutMustReturnTheAmount() {
        CommercialTransaction t = tx(TxType.PAYOUT, TxStatus.REVERSED, 2_000, 40, 2_040);
        Posting back = new Posting(LedgerEntryType.ADJUSTMENT, 2_000, 0, 0);
        assertTrue(ReconciliationRules.check(t, List.of(hold(2_040), debit(2_040, 40), back), NOW).isEmpty());
        assertEquals(List.of("LEDGER_MISSING"), types(ReconciliationRules.check(t, List.of(hold(2_040), debit(2_040, 40)), NOW)));
    }

    @Test
    void openPaymentIsStuckOnlyAfterItsExpiryAndGrace() {
        CommercialTransaction t = tx(TxType.PAYMENT, TxStatus.PENDING, 1_000, 30, 970);
        t.setExpiresAt(Instant.parse("2026-10-14T11:00:00Z"));
        assertEquals(List.of("STUCK_PENDING"), types(ReconciliationRules.check(t, List.of(), NOW)));
        t.setExpiresAt(Instant.parse("2026-10-14T11:58:00Z"));
        assertTrue(ReconciliationRules.check(t, List.of(), NOW).isEmpty());
    }

    @Test
    void openOutflowIsStuckAfterThirtyMinutes() {
        CommercialTransaction t = tx(TxType.SETTLEMENT, TxStatus.PENDING, 5_000, 100, 5_100);
        assertEquals(List.of("STUCK_PENDING"), types(ReconciliationRules.check(t, List.of(hold(5_100)), NOW)));
        t.setCreatedAt(Instant.parse("2026-10-14T11:45:00Z"));
        assertTrue(ReconciliationRules.check(t, List.of(hold(5_100)), NOW).isEmpty());
    }

    @Test
    void finishedButUnappliedForTooLongIsFlagged() {
        CommercialTransaction t = tx(TxType.PAYMENT, TxStatus.SUCCESS, 1_000, 30, 970);
        t.setUpdatedAt(Instant.parse("2026-10-14T11:00:00Z"));
        List<String> found = types(ReconciliationRules.check(t, List.of(credit(970, 30)), NOW));
        assertEquals(List.of("NOT_APPLIED"), found);
    }
}
