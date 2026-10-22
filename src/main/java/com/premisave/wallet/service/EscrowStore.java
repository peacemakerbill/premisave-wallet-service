package com.premisave.wallet.service;

import com.premisave.wallet.entity.EscrowHold;
import com.premisave.wallet.entity.EscrowPayout;
import com.premisave.wallet.enums.EscrowHoldStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Where escrow holds and payouts are kept, as a set of small guarded operations. Every method that changes
 * a document is one atomic, conditional write: it applies only if the document is still in the state the caller
 * expects, and it reports whether this call was the one that changed it. EscrowService builds the money flow out
 * of these, so two requests racing for the same reference or the same remaining amount always have exactly one winner.
 * MongoEscrowStore is the real implementation.
 */
public interface EscrowStore {

    /** Thrown by insertHold / insertPayout when the reference is already taken (the unique index). */
    class ReferenceTakenException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ReferenceTakenException(String reference) {
            super("Reference already used: " + reference);
        }
    }

    /** Thrown by reserve() when the hold is not in a state that can be paid out (it maps to 409). */
    class HoldNotPayableException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public HoldNotPayableException(String message) {
            super(message);
        }
    }

    /** Whether reserve() took the amount out of the hold, or an earlier call for the same payout already had. */
    enum ReserveOutcome { APPLIED, ALREADY }

    /**
     * The status a hold has for the amount still held: PAID_OUT when nothing is left, HELD when all of it is
     * left, PARTIALLY_PAID in between.
     */
    static EscrowHoldStatus statusFor(BigDecimal remaining, BigDecimal held) {
        if (remaining.signum() <= 0) return EscrowHoldStatus.PAID_OUT;
        if (remaining.compareTo(held) >= 0) return EscrowHoldStatus.HELD;
        return EscrowHoldStatus.PARTIALLY_PAID;
    }

    // ----- holds -----

    Optional<EscrowHold> findHold(String reference);

    Optional<EscrowHold> findHoldById(String id);

    /** Inserts a new PENDING hold. Throws ReferenceTakenException if the reference exists. */
    EscrowHold insertHold(EscrowHold hold);

    /** FAILED to PENDING, replacing the attempt's fields with those of fresh. True only for the one call that did it. */
    boolean reopenHold(String id, EscrowHold fresh);

    /** PENDING to HELD. */
    boolean holdPendingToHeld(String id);

    /** PENDING to FAILED with a reason. */
    boolean holdPendingToFailed(String id, String reason);

    /**
     * Takes amount out of the hold's remaining amount for this payout, atomically and only while the hold is
     * HELD or PARTIALLY_PAID and has at least that much left; recomputes the hold status. Repeating it for the
     * same payout does nothing and returns ALREADY. Throws ResourceNotFoundException (no such hold),
     * HoldNotPayableException (hold not payable) or InsufficientFundsException (less left than amount).
     */
    ReserveOutcome reserve(String holdId, String payoutId, BigDecimal amount);

    /** Undoes reserve() for this payout. Does nothing if it was not reserved (or was already undone). */
    void unreserve(String holdId, String payoutId, BigDecimal amount);

    List<EscrowHold> stalePendingHolds(LocalDateTime cutoff, int limit);

    /** Sum of what is still held across open holds (HELD and PARTIALLY_PAID). Money that belongs to no wallet. */
    BigDecimal openHeldTotal();

    // ----- payouts -----

    Optional<EscrowPayout> findPayout(String reference);

    Optional<EscrowPayout> findPayoutById(String id);

    List<EscrowPayout> payoutsOfHold(String holdId);

    EscrowPayout insertPayout(EscrowPayout payout);

    boolean reopenPayout(String id, EscrowPayout fresh);

    boolean payoutPendingToSuccess(String id);

    boolean payoutPendingToFailed(String id, String reason);

    List<EscrowPayout> stalePendingPayouts(LocalDateTime cutoff, int limit);
}
