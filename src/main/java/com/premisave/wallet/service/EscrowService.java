package com.premisave.wallet.service;

import com.premisave.wallet.config.EscrowProperties;
import com.premisave.wallet.dto.EscrowDetailsResponse;
import com.premisave.wallet.dto.EscrowHoldRequest;
import com.premisave.wallet.dto.EscrowHoldResponse;
import com.premisave.wallet.dto.EscrowPayoutRequest;
import com.premisave.wallet.dto.EscrowPayoutResponse;
import com.premisave.wallet.entity.CompanyLedgerEntry;
import com.premisave.wallet.entity.EscrowHold;
import com.premisave.wallet.entity.EscrowPayout;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.EscrowHoldStatus;
import com.premisave.wallet.enums.EscrowPayoutStatus;
import com.premisave.wallet.exception.EscrowCallerNotAllowedException;
import com.premisave.wallet.exception.InsufficientFundsException;
import com.premisave.wallet.exception.ResourceNotFoundException;
import com.premisave.wallet.exception.WalletFrozenException;
import com.premisave.wallet.exception.WalletNotFoundException;
import com.premisave.wallet.repository.CompanyLedgerRepository;
import com.premisave.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Escrow for the services that sell something now and pay out later (for example the listing service: a tenant pays
 * when booking, the owner is paid after check-in, and a cancellation sends part or all of it back).
 *
 * WHY: paying that with two ordinary transfers (payer to a holding user, holding user to payee) charges commission
 * twice. Here the commission is charged once, when the money goes in. The money then sits in an EscrowHold owned by
 * wallet-service, in no user's wallet, and is paid out later with no further commission.
 *
 * Booking price P, rate r, fee(x) = round_half_up(x * r, 2), the same rate source, rounding and "charged on top"
 * rule as /internal/transfer:
 *  - hold:    the sender is debited P + fee(P). The company ledger gets fee(P). The hold keeps P.
 *  - payout:  the recipient is credited exactly the payout amount. No commission. The payouts of one hold add up to
 *             at most P, so an owner share of 50.00 and a refund of 50.00 on a 100.00 hold leave nothing behind.
 *  - the commission is never reversed.
 *
 * HOW IT STAYS SAFE WITHOUT A MONGO TRANSACTION MANAGER (the wallet has none): every step is one conditional write,
 * and a record is claimed (PENDING, unique reference) before any money moves. A hold is: claim the reference, debit the
 * sender, write the commission, mark HELD. A payout is: claim the reference, take the amount out of the hold, credit
 * the recipient, mark SUCCESS. A request that dies half way leaves a PENDING record; settleHold / settlePayout, run by
 * EscrowRecoveryJob, look at whether the wallet movement happened (EscrowWalletMovements.cancel rules it out for good
 * if it did not, so the request itself can never apply it afterwards) and either finish the record or roll it back.
 * Nothing stays PENDING forever and no money is lost or created either way.
 *
 * REFERENCES are idempotency keys, backed by a unique index. A reference that is PENDING, HELD or SUCCESS answers 409
 * and moves nothing; one that ended FAILED (nothing moved) can be used again and reopens the same record.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EscrowService {

    private static final String LEDGER_TYPE = "COMMISSION_TRANSFER";
    private static final int MAX_REFERENCE_LENGTH = 200;
    private static final int SWEEP_BATCH = 100;

    private final EscrowStore store;
    private final EscrowWalletMovements movements;
    private final WalletRepository walletRepository;
    private final CommissionService commissionService;
    private final CompanyLedgerRepository companyLedgerRepository;
    private final EscrowProperties properties;

    // ------------------------------------------------------------------ hold

    /** POST /internal/escrow/hold: debit the sender once and create the hold. */
    public EscrowHoldResponse hold(EscrowHoldRequest request) {
        requireAllowed(request.getInitiatedBy());
        String reference = cleanReference(request.getReference());
        BigDecimal amount = cleanAmount(request.getAmount());

        // A reference that is already in use answers 409 before anything else is looked at.
        EscrowHold previous = store.findHold(reference).orElse(null);
        if (previous != null && previous.getStatus() != EscrowHoldStatus.FAILED) {
            throw holdExists(reference, previous.getStatus());
        }

        Wallet sender = walletRepository.findByUserId(request.getSenderUserId())
                .orElseThrow(() -> new WalletNotFoundException(
                        "Sender wallet not found for userId: " + request.getSenderUserId()));
        Wallet beneficiary = findByEmail(request.getBeneficiaryEmail())
                .orElseThrow(() -> new WalletNotFoundException(
                        "Beneficiary wallet not found for account: " + request.getBeneficiaryEmail()));
        if (sender.getId().equals(beneficiary.getId())) {
            throw new IllegalArgumentException(
                    "Cannot hold escrow between a wallet and itself: the sender and the beneficiary must be different");
        }
        if (sender.isFrozen()) {
            throw new WalletFrozenException("Wallet is frozen and cannot perform transactions until it is unfrozen");
        }
        // Checked now, so a booking cannot be paid for when the owner could not be paid later.
        if (beneficiary.isFrozen()) {
            throw new WalletFrozenException("Beneficiary wallet is currently frozen and cannot receive funds");
        }

        BigDecimal commission = commissionService.calculateInternalTransferCommission(amount);
        BigDecimal totalDebited = amount.add(commission);
        BigDecimal balance = sender.getBalance() == null ? BigDecimal.ZERO : sender.getBalance();
        if (balance.compareTo(totalDebited) < 0) {
            throw new InsufficientFundsException("Insufficient balance. Available: " + balance);
        }

        EscrowHold attempt = new EscrowHold();
        attempt.setReference(reference);
        attempt.setSenderUserId(sender.getUserId());
        attempt.setSenderWalletId(sender.getId());
        attempt.setSenderEmail(sender.getAccountNumber());
        attempt.setBeneficiaryUserId(beneficiary.getUserId());
        attempt.setBeneficiaryWalletId(beneficiary.getId());
        attempt.setBeneficiaryEmail(beneficiary.getAccountNumber());
        attempt.setAmount(amount);
        attempt.setCommission(commission);
        attempt.setCommissionRate(commissionService.getInternalTransferRate());
        attempt.setTotalDebited(totalDebited);
        attempt.setRemainingAmount(amount);
        attempt.setCurrency(Currency.USD);
        attempt.setStatus(EscrowHoldStatus.PENDING);
        attempt.setDescription(request.getDescription());
        attempt.setInitiatedBy(request.getInitiatedBy().trim());
        attempt.setAttempts(1);
        attempt.setPendingSince(LocalDateTime.now());

        // Claim the reference. Exactly one of any number of simultaneous identical requests gets past this.
        EscrowHold hold;
        if (previous == null) {
            try {
                hold = store.insertHold(attempt);
            } catch (EscrowStore.ReferenceTakenException taken) {
                throw holdExists(reference, null);
            }
        } else {
            attempt.setId(previous.getId());
            attempt.setAttempts(previous.getAttempts() + 1);
            if (!store.reopenHold(previous.getId(), attempt)) {
                throw holdExists(reference, null);
            }
            hold = attempt;
        }

        try {
            movements.debit(sender.getId(), debitKey(hold), totalDebited, reference, holdStatement(hold));
        } catch (RuntimeException e) {
            // A refusal, or a step the recovery sweep cancelled: nothing moved, so the hold can be failed now.
            // Anything else (for example the database being down) is left PENDING for the sweep to settle.
            if (isWalletRefusal(e) || e instanceof IllegalStateException) {
                settleHold(hold, e.getMessage());
            }
            throw e;
        }

        // The sender has been debited. From here the hold is completed whatever happens (the sweep finishes it
        // if this request dies, because it sees the debit).
        writeCommission(hold);
        completeHold(hold.getId());

        EscrowHold done = store.findHoldById(hold.getId()).orElse(hold);
        log.info("Escrow hold created | Ref: {} | Sender: {} | Beneficiary: {} | Amount: {} | Commission: {} | TotalDebited: {} | InitiatedBy: {}",
                reference, done.getSenderEmail(), done.getBeneficiaryEmail(), amount, commission, totalDebited,
                request.getInitiatedBy());
        return toHoldResponse(done);
    }

    // ---------------------------------------------------------------- payout

    /** POST /internal/escrow/{holdReference}/payouts: pay part or all of what is left to the sender or the beneficiary. */
    public EscrowPayoutResponse payout(String holdReference, EscrowPayoutRequest request) {
        requireAllowed(request.getInitiatedBy());
        String holdRef = cleanReference(holdReference);
        String reference = cleanReference(request.getReference());
        BigDecimal amount = cleanAmount(request.getAmount());

        EscrowPayout previous = store.findPayout(reference).orElse(null);
        if (previous != null
                && (previous.getStatus() != EscrowPayoutStatus.FAILED || !holdRef.equals(previous.getHoldReference()))) {
            throw payoutExists(reference, previous);
        }

        EscrowHold hold = store.findHold(holdRef)
                .orElseThrow(() -> new ResourceNotFoundException("Escrow not found for reference: " + holdRef));

        // Only the sender (a refund) or the beneficiary (the owner) can be paid, so a leaked key cannot redirect held money.
        String role = roleOf(hold, request.getRecipientAccountNumber());
        if (role == null) {
            throw new IllegalArgumentException(
                    "The recipient must be the sender or the beneficiary of this escrow hold");
        }
        if (hold.getStatus() != EscrowHoldStatus.HELD && hold.getStatus() != EscrowHoldStatus.PARTIALLY_PAID) {
            throw new EscrowStore.HoldNotPayableException(
                    "Escrow hold " + holdRef + " is " + hold.getStatus() + " and cannot be paid out");
        }
        if (hold.getRemainingAmount().compareTo(amount) < 0) {
            throw new InsufficientFundsException("Payout of " + amount + " is more than the "
                    + hold.getRemainingAmount() + " left in escrow hold " + holdRef);
        }

        boolean toSender = "SENDER".equals(role);
        String walletId = toSender ? hold.getSenderWalletId() : hold.getBeneficiaryWalletId();
        String recipientUserId = toSender ? hold.getSenderUserId() : hold.getBeneficiaryUserId();
        String recipientEmail = toSender ? hold.getSenderEmail() : hold.getBeneficiaryEmail();
        Wallet recipient = walletRepository.findById(walletId)
                .orElseThrow(() -> new WalletNotFoundException("Recipient wallet not found for account: " + recipientEmail));
        if (recipient.isFrozen()) {
            throw new WalletFrozenException("Recipient wallet is currently frozen and cannot receive funds");
        }

        EscrowPayout attempt = new EscrowPayout();
        attempt.setReference(reference);
        attempt.setHoldId(hold.getId());
        attempt.setHoldReference(holdRef);
        attempt.setRecipientRole(role);
        attempt.setRecipientUserId(recipientUserId);
        attempt.setRecipientWalletId(walletId);
        attempt.setRecipientEmail(recipientEmail);
        attempt.setAmount(amount);
        attempt.setCurrency(Currency.USD);
        attempt.setStatus(EscrowPayoutStatus.PENDING);
        attempt.setDescription(request.getDescription());
        attempt.setInitiatedBy(request.getInitiatedBy().trim());
        attempt.setAttempts(1);
        attempt.setPendingSince(LocalDateTime.now());

        // Claim the reference.
        EscrowPayout payout;
        if (previous == null) {
            try {
                payout = store.insertPayout(attempt);
            } catch (EscrowStore.ReferenceTakenException taken) {
                throw payoutExists(reference, null);
            }
        } else {
            attempt.setId(previous.getId());
            attempt.setAttempts(previous.getAttempts() + 1);
            if (!store.reopenPayout(previous.getId(), attempt)) {
                throw payoutExists(reference, null);
            }
            payout = attempt;
        }

        // Take the amount out of the hold first, atomically and only if enough is left, so two payouts racing
        // for the same money can never overspend it.
        try {
            store.reserve(hold.getId(), payout.getId(), amount);
        } catch (RuntimeException e) {
            if (e instanceof ResourceNotFoundException || e instanceof InsufficientFundsException
                    || e instanceof EscrowStore.HoldNotPayableException) {
                // Refused, nothing was taken out of the hold.
                store.payoutPendingToFailed(payout.getId(), e.getMessage());
            }
            throw e;
        }

        try {
            movements.credit(walletId, creditKey(payout), amount, reference, payoutStatement(payout));
        } catch (RuntimeException e) {
            // Refused (the wallet froze or vanished meanwhile) or a step the sweep cancelled: give the amount back to
            // the hold and fail the payout. Anything else is left PENDING for the sweep to settle.
            if (isWalletRefusal(e) || e instanceof IllegalStateException) {
                settlePayout(payout, e.getMessage());
            }
            throw e;
        }

        completePayout(payout.getId());

        EscrowPayout done = store.findPayoutById(payout.getId()).orElse(payout);
        EscrowHold after = store.findHoldById(hold.getId()).orElse(hold);
        log.info("Escrow payout | Hold: {} | Ref: {} | Recipient: {} | Amount: {} | HoldRemaining: {} | InitiatedBy: {}",
                holdRef, reference, recipientEmail, amount, after.getRemainingAmount(), request.getInitiatedBy());
        return toPayoutResponse(done, after);
    }

    // ---------------------------------------------------------------- lookup

    /** GET /internal/escrow/{holdReference}: the hold and every payout made from it. */
    public EscrowDetailsResponse get(String holdReference) {
        String holdRef = cleanReference(holdReference);
        EscrowHold hold = store.findHold(holdRef)
                .orElseThrow(() -> new ResourceNotFoundException("Escrow not found for reference: " + holdRef));

        EscrowDetailsResponse r = new EscrowDetailsResponse();
        r.setReference(hold.getReference());
        r.setStatus(hold.getStatus());
        r.setSenderEmail(hold.getSenderEmail());
        r.setBeneficiaryEmail(hold.getBeneficiaryEmail());
        r.setAmount(hold.getAmount());
        r.setCommission(hold.getCommission());
        r.setTotalDebited(hold.getTotalDebited());
        r.setRemainingAmount(hold.getRemainingAmount());
        r.setCurrency(hold.getCurrency());
        r.setFailureReason(hold.getFailureReason());
        r.setCreatedAt(hold.getCreatedAt());
        for (EscrowPayout p : store.payoutsOfHold(hold.getId())) {
            EscrowDetailsResponse.Payout item = new EscrowDetailsResponse.Payout();
            item.setReference(p.getReference());
            item.setRecipientEmail(p.getRecipientEmail());
            item.setAmount(p.getAmount());
            item.setStatus(p.getStatus());
            item.setFailureReason(p.getFailureReason());
            item.setCreatedAt(p.getCreatedAt());
            r.getPayouts().add(item);
        }
        return r;
    }

    /**
     * What is held right now across open holds. It belongs to no wallet, so a total of what the platform owes
     * users is the sum of the wallet balances plus this (see AdminReportService).
     */
    public BigDecimal openHeldTotal() {
        return store.openHeldTotal();
    }

    // -------------------------------------------------------------- recovery

    /**
     * Settles every hold and payout that has been PENDING for longer than escrow.stale-pending-minutes. Safe to run
     * from several instances at once and to run again after a crash: each step is conditional and repeating it does
     * nothing. Called by EscrowRecoveryJob.
     */
    public void recoverStale() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(Math.max(1, properties.getStalePendingMinutes()));
        for (EscrowPayout p : store.stalePendingPayouts(cutoff, SWEEP_BATCH)) {
            try {
                settlePayout(p, "Interrupted before the recipient was credited; nothing moved");
            } catch (RuntimeException e) {
                log.error("Could not settle the stale escrow payout {} ({})", p.getReference(), p.getId(), e);
            }
        }
        for (EscrowHold h : store.stalePendingHolds(cutoff, SWEEP_BATCH)) {
            try {
                settleHold(h, "Interrupted before any money moved; the sender was not debited");
            } catch (RuntimeException e) {
                log.error("Could not settle the stale escrow hold {} ({})", h.getReference(), h.getId(), e);
            }
        }
    }

    /**
     * Settles a hold that is still PENDING. The debit's movement key is cancelled first: if that succeeds the debit
     * never happened and never will, so the hold fails; if it reports the debit was already applied, the hold is
     * finished (commission, statement row, HELD).
     */
    private void settleHold(EscrowHold seen, String failureReason) {
        String holdId = seen.getId();
        EscrowHold hold = store.findHoldById(holdId).orElse(null);
        // Only the attempt that was seen: a slow request from an earlier attempt must never touch a newer one.
        if (hold == null || hold.getStatus() != EscrowHoldStatus.PENDING || hold.getAttempts() != seen.getAttempts()) return;

        if (movements.cancel(hold.getSenderWalletId(), debitKey(hold))) {
            if (store.holdPendingToFailed(holdId, failureReason)) {
                log.warn("Escrow hold {} failed, nothing moved: {}", hold.getReference(), failureReason);
            }
            return;
        }
        writeCommission(hold);
        movements.ensureHistory(hold.getSenderWalletId(), hold.getTotalDebited().negate(), hold.getReference(),
                holdStatement(hold));
        if (store.holdPendingToHeld(holdId)) {
            log.warn("Escrow hold {} was interrupted after the sender was debited; completed", hold.getReference());
        }
    }

    /**
     * Settles a payout that is still PENDING, the same way: if the credit can be ruled out, the amount goes back to
     * the hold and the payout fails; if the credit was already applied, the payout is finished. The amount is put
     * back before the payout is marked FAILED, so a crash in between is repaired by running this again.
     */
    private void settlePayout(EscrowPayout seen, String failureReason) {
        String payoutId = seen.getId();
        EscrowPayout payout = store.findPayoutById(payoutId).orElse(null);
        if (payout == null || payout.getStatus() != EscrowPayoutStatus.PENDING
                || payout.getAttempts() != seen.getAttempts()) return;

        if (movements.cancel(payout.getRecipientWalletId(), creditKey(payout))) {
            store.unreserve(payout.getHoldId(), payout.getId(), payout.getAmount());
            if (store.payoutPendingToFailed(payoutId, failureReason)) {
                log.warn("Escrow payout {} failed, the amount went back to the hold: {}", payout.getReference(), failureReason);
            }
            return;
        }
        movements.ensureHistory(payout.getRecipientWalletId(), payout.getAmount(), payout.getReference(),
                payoutStatement(payout));
        if (store.payoutPendingToSuccess(payoutId)) {
            log.warn("Escrow payout {} was interrupted after the recipient was credited; completed", payout.getReference());
        }
    }

    // --------------------------------------------------------------- helpers

    private void completeHold(String holdId) {
        if (store.holdPendingToHeld(holdId)) return;
        EscrowHold now = store.findHoldById(holdId).orElse(null);
        if (now != null && now.getStatus() == EscrowHoldStatus.FAILED) {
            // Cannot happen: a FAILED hold is only set after the debit was ruled out for good.
            log.error("Escrow hold {} is FAILED although its sender was debited; needs a manual look", now.getReference());
        }
        // HELD, PARTIALLY_PAID or PAID_OUT: the recovery sweep completed it first.
    }

    private void completePayout(String payoutId) {
        if (store.payoutPendingToSuccess(payoutId)) return;
        EscrowPayout now = store.findPayoutById(payoutId).orElse(null);
        if (now != null && now.getStatus() == EscrowPayoutStatus.FAILED) {
            // Cannot happen: a FAILED payout is only set after the credit was ruled out for good.
            log.error("Escrow payout {} is FAILED although the recipient was credited; needs a manual look", now.getReference());
        }
    }

    /**
     * The commission goes to the company ledger exactly as /internal/transfer does (type COMMISSION_TRANSFER, so the
     * existing reports count it), once per hold: the entry has a fixed id, so writing it again changes nothing.
     */
    private void writeCommission(EscrowHold hold) {
        if (hold.getCommission() == null || hold.getCommission().signum() <= 0) return;
        String id = "escrow-commission-" + hold.getId();
        if (companyLedgerRepository.existsById(id)) return;

        CompanyLedgerEntry entry = new CompanyLedgerEntry();
        entry.setId(id);
        entry.setAmount(hold.getCommission());
        entry.setCurrency(Currency.USD);
        entry.setType(LEDGER_TYPE);
        entry.setDescription("Commission on escrow hold for " + hold.getBeneficiaryEmail());
        entry.setSourceType("ESCROW_HOLD");
        entry.setSourceId(hold.getId());
        entry.setSourceReference(hold.getReference());
        entry.setRateApplied(hold.getCommissionRate());
        entry.setGrossAmount(hold.getAmount());
        entry.setUserId(hold.getSenderUserId());
        entry.setCreatedAt(LocalDateTime.now());
        companyLedgerRepository.save(entry);
    }

    private void requireAllowed(String initiatedBy) {
        List<String> allowed = properties.getAllowedInitiators();
        if (allowed == null || allowed.isEmpty()) return;
        String caller = initiatedBy == null ? "" : initiatedBy.trim();
        for (String a : allowed) {
            if (a != null && a.trim().equalsIgnoreCase(caller)) return;
        }
        throw new EscrowCallerNotAllowedException(
                "The caller \"" + caller + "\" is not allowed to use the escrow endpoints");
    }

    private static String cleanReference(String reference) {
        String r = reference == null ? "" : reference.trim();
        if (r.isEmpty()) throw new IllegalArgumentException("reference is required, it is the idempotency key");
        if (r.length() > MAX_REFERENCE_LENGTH) {
            throw new IllegalArgumentException("reference must be at most " + MAX_REFERENCE_LENGTH + " characters");
        }
        return r;
    }

    /** Positive and at most 2 decimals, returned at scale 2. */
    private static BigDecimal cleanAmount(BigDecimal amount) {
        if (amount == null) throw new IllegalArgumentException("Amount is required");
        if (amount.signum() <= 0) throw new IllegalArgumentException("Amount must be greater than zero");
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("Amount can have at most 2 decimal places");
        }
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    private Optional<Wallet> findByEmail(String email) {
        if (email == null || email.isBlank()) return Optional.empty();
        String ref = email.trim();
        Optional<Wallet> exact = walletRepository.findByAccountNumber(ref);
        if (exact.isPresent()) return exact;
        String lower = ref.toLowerCase(Locale.ROOT);
        return lower.equals(ref) ? Optional.empty() : walletRepository.findByAccountNumber(lower);
    }

    /** "SENDER", "BENEFICIARY", or null when the email is neither. Compared ignoring case. */
    private static String roleOf(EscrowHold hold, String email) {
        String e = email == null ? "" : email.trim();
        if (e.equalsIgnoreCase(hold.getSenderEmail())) return "SENDER";
        if (e.equalsIgnoreCase(hold.getBeneficiaryEmail())) return "BENEFICIARY";
        return null;
    }

    private static boolean isWalletRefusal(RuntimeException e) {
        return e instanceof InsufficientFundsException || e instanceof WalletFrozenException
                || e instanceof WalletNotFoundException;
    }

    /** One movement key per attempt, so a retry after a cancelled attempt gets a fresh one. */
    private static String debitKey(EscrowHold hold) {
        return hold.getId() + "-D" + hold.getAttempts();
    }

    private static String creditKey(EscrowPayout payout) {
        return payout.getId() + "-C" + payout.getAttempts();
    }

    private static String holdStatement(EscrowHold hold) {
        return "Escrow hold for " + hold.getReference() + suffix(hold.getDescription()) + " (incl. commission)";
    }

    private static String payoutStatement(EscrowPayout payout) {
        String what = "SENDER".equals(payout.getRecipientRole()) ? "Escrow refund for " : "Escrow payout for ";
        return what + payout.getHoldReference() + suffix(payout.getDescription());
    }

    private static String suffix(String description) {
        return description != null && !description.isBlank() ? " - " + description.trim() : "";
    }

    private static IllegalStateException holdExists(String reference, EscrowHoldStatus status) {
        return new IllegalStateException("Escrow hold already exists for reference: " + reference
                + (status != null ? " (status " + status + ")" : ""));
    }

    private static IllegalStateException payoutExists(String reference, EscrowPayout existing) {
        return new IllegalStateException("Escrow payout already exists for reference: " + reference
                + (existing != null ? " (status " + existing.getStatus() + ")" : ""));
    }

    private static EscrowHoldResponse toHoldResponse(EscrowHold hold) {
        EscrowHoldResponse r = new EscrowHoldResponse();
        r.setReference(hold.getReference());
        r.setStatus(hold.getStatus());
        r.setSenderEmail(hold.getSenderEmail());
        r.setBeneficiaryEmail(hold.getBeneficiaryEmail());
        r.setAmount(hold.getAmount());
        r.setCommission(hold.getCommission());
        r.setTotalDebited(hold.getTotalDebited());
        r.setRemainingAmount(hold.getRemainingAmount());
        r.setCurrency(hold.getCurrency());
        r.setCreatedAt(hold.getCreatedAt());
        return r;
    }

    private static EscrowPayoutResponse toPayoutResponse(EscrowPayout payout, EscrowHold hold) {
        EscrowPayoutResponse r = new EscrowPayoutResponse();
        r.setHoldReference(hold.getReference());
        r.setReference(payout.getReference());
        r.setStatus(payout.getStatus());
        r.setRecipientEmail(payout.getRecipientEmail());
        r.setAmount(payout.getAmount());
        r.setHoldRemainingAmount(hold.getRemainingAmount());
        r.setHoldStatus(hold.getStatus());
        r.setCurrency(payout.getCurrency());
        return r;
    }
}
