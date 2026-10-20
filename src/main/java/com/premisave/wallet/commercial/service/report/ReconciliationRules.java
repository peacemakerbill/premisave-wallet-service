package com.premisave.wallet.commercial.service.report;

import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.enums.LedgerEntryType;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.enums.TxType;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks one transaction against the ledger lines that were posted for it.
 * Every rule is about the merchant's own books: money a transaction says it
 * moved must appear in the ledger in the right amount, and nothing may be
 * posted for a transaction that did not succeed. Provider statements are not
 * part of this check.
 */
public final class ReconciliationRules {

    static final Duration PAYMENT_GRACE = Duration.ofMinutes(5);
    static final Duration OUTBOUND_LIMIT = Duration.ofMinutes(30);
    static final Duration UNAPPLIED_GRACE = Duration.ofMinutes(5);

    private ReconciliationRules() {}

    /** A ledger line reduced to what the rules look at. */
    public record Posting(LedgerEntryType type, long availableDelta, long heldDelta, long feeCents) {}

    /** One disagreement. ledgerCents is what the ledger holds, or null when nothing was posted. */
    public record Finding(String type, String objectId, long amountCents, Long ledgerCents, String note) {}

    public static List<Finding> check(CommercialTransaction tx, List<Posting> postings, Instant now) {
        List<Finding> out = new ArrayList<>();
        TxStatus status = tx.getStatus();

        if (!status.isFinal()) {
            Instant deadline = tx.getType() == TxType.PAYMENT && tx.getExpiresAt() != null
                    ? tx.getExpiresAt().plus(PAYMENT_GRACE)
                    : tx.getCreatedAt().plus(OUTBOUND_LIMIT);
            if (now.isAfter(deadline)) {
                out.add(new Finding("STUCK_PENDING", tx.getId(), tx.getAmountCents(), null,
                        "Still " + status + " well past its deadline"));
            }
            return out;
        }

        if (!tx.isLedgerApplied() && tx.getUpdatedAt() != null && now.isAfter(tx.getUpdatedAt().plus(UNAPPLIED_GRACE))) {
            out.add(new Finding("NOT_APPLIED", tx.getId(), tx.getAmountCents(), null,
                    "Reached " + status + " but its money step has not been recorded as done"));
        }

        if (tx.getType() == TxType.PAYMENT) {
            checkPayment(tx, postings, out);
        } else if (tx.getType().usesHold()) {
            checkHeld(tx, postings, out);
        }
        return out;
    }

    private static void checkPayment(CommercialTransaction tx, List<Posting> postings, List<Finding> out) {
        List<Posting> credits = of(postings, LedgerEntryType.COLLECTION_CREDIT);
        if (tx.getStatus() == TxStatus.SUCCESS) {
            if (credits.isEmpty()) {
                out.add(new Finding("LEDGER_MISSING", tx.getId(), tx.getSettleCents(), null,
                        "Succeeded but no credit was posted to the balance"));
            } else if (sum(credits, Posting::availableDelta) != tx.getSettleCents()) {
                out.add(new Finding("AMOUNT_MISMATCH", tx.getId(), tx.getSettleCents(),
                        sum(credits, Posting::availableDelta), "Credited amount differs from the payment's net amount"));
            }
        } else if (!credits.isEmpty()) {
            out.add(new Finding("POSTING_WITHOUT_SUCCESS", tx.getId(), tx.getSettleCents(),
                    sum(credits, Posting::availableDelta), "A credit was posted for a payment that is " + tx.getStatus()));
        }
    }

    private static void checkHeld(CommercialTransaction tx, List<Posting> postings, List<Finding> out) {
        List<Posting> holds = of(postings, LedgerEntryType.PAYOUT_HOLD);
        List<Posting> releases = of(postings, LedgerEntryType.PAYOUT_HOLD_RELEASE);
        List<Posting> debits = of(postings, LedgerEntryType.PAYOUT_DEBIT);
        List<Posting> reversals = of(postings, LedgerEntryType.ADJUSTMENT);

        if (holds.isEmpty()) {
            out.add(new Finding("HOLD_MISSING", tx.getId(), tx.getSettleCents(), null,
                    "No hold was posted for the amount and fee"));
        } else if (sum(holds, Posting::heldDelta) != tx.getSettleCents()) {
            out.add(new Finding("AMOUNT_MISMATCH", tx.getId(), tx.getSettleCents(), sum(holds, Posting::heldDelta),
                    "Held amount differs from the amount plus fee"));
        }

        switch (tx.getStatus()) {
            case SUCCESS, REVERSED -> {
                if (debits.isEmpty()) {
                    out.add(new Finding("LEDGER_MISSING", tx.getId(), tx.getSettleCents(), null,
                            "Succeeded but the held money was never debited"));
                } else if (-sum(debits, Posting::heldDelta) != tx.getSettleCents() || sum(debits, Posting::feeCents) != tx.getFeeCents()) {
                    out.add(new Finding("AMOUNT_MISMATCH", tx.getId(), tx.getSettleCents(), -sum(debits, Posting::heldDelta),
                            "Debited amount or fee differs from the transaction"));
                }
                if (!releases.isEmpty()) {
                    out.add(new Finding("POSTING_WITHOUT_SUCCESS", tx.getId(), tx.getSettleCents(),
                            sum(releases, Posting::availableDelta), "The hold was released although the transaction succeeded"));
                }
                if (tx.getStatus() == TxStatus.REVERSED && sum(reversals, Posting::availableDelta) != tx.getAmountCents()) {
                    out.add(new Finding("LEDGER_MISSING", tx.getId(), tx.getAmountCents(),
                            reversals.isEmpty() ? null : sum(reversals, Posting::availableDelta),
                            "Reversed by the provider but the amount was not returned to the balance"));
                }
            }
            default -> {
                if (releases.isEmpty()) {
                    out.add(new Finding("HOLD_NOT_RELEASED", tx.getId(), tx.getSettleCents(), null,
                            tx.getStatus() + " but the held money was not returned"));
                }
                if (!debits.isEmpty()) {
                    out.add(new Finding("POSTING_WITHOUT_SUCCESS", tx.getId(), tx.getSettleCents(),
                            -sum(debits, Posting::heldDelta), "Money was debited although the transaction is " + tx.getStatus()));
                }
            }
        }
    }

    private static List<Posting> of(List<Posting> postings, LedgerEntryType type) {
        return postings.stream().filter(p -> p.type() == type).toList();
    }

    private static long sum(List<Posting> postings, java.util.function.ToLongFunction<Posting> field) {
        return postings.stream().mapToLong(field).sum();
    }
}
