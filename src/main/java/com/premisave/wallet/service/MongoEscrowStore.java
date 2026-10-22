package com.premisave.wallet.service;

import com.premisave.wallet.entity.EscrowHold;
import com.premisave.wallet.entity.EscrowPayout;
import com.premisave.wallet.enums.EscrowHoldStatus;
import com.premisave.wallet.enums.EscrowPayoutStatus;
import com.premisave.wallet.exception.InsufficientFundsException;
import com.premisave.wallet.exception.ResourceNotFoundException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * MongoDB implementation of EscrowStore. Every change is one conditional updateFirst on one document, so
 * MongoDB itself decides the winner when two requests race; the amounts are compared for equality with the value
 * that was read (the same technique the wallet balance updates use), which also works when BigDecimal is stored
 * as text.
 */
@Component
public class MongoEscrowStore implements EscrowStore {

    private static final int MAX_ATTEMPTS = 8;

    private final MongoTemplate mongo;

    public MongoEscrowStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    // ----- holds -----

    @Override
    public Optional<EscrowHold> findHold(String reference) {
        return Optional.ofNullable(mongo.findOne(Query.query(Criteria.where("reference").is(reference)), EscrowHold.class));
    }

    @Override
    public Optional<EscrowHold> findHoldById(String id) {
        return Optional.ofNullable(mongo.findById(id, EscrowHold.class));
    }

    @Override
    public EscrowHold insertHold(EscrowHold hold) {
        LocalDateTime now = LocalDateTime.now();
        if (hold.getCreatedAt() == null) hold.setCreatedAt(now);
        hold.setUpdatedAt(now);
        try {
            return mongo.insert(hold);
        } catch (DuplicateKeyException e) {
            throw new ReferenceTakenException(hold.getReference());
        }
    }

    @Override
    public boolean reopenHold(String id, EscrowHold fresh) {
        Criteria guard = Criteria.where("id").is(id).and("status").is(EscrowHoldStatus.FAILED);
        LocalDateTime now = LocalDateTime.now();
        Update update = new Update()
                .set("senderUserId", fresh.getSenderUserId())
                .set("senderWalletId", fresh.getSenderWalletId())
                .set("senderEmail", fresh.getSenderEmail())
                .set("beneficiaryUserId", fresh.getBeneficiaryUserId())
                .set("beneficiaryWalletId", fresh.getBeneficiaryWalletId())
                .set("beneficiaryEmail", fresh.getBeneficiaryEmail())
                .set("amount", fresh.getAmount())
                .set("commission", fresh.getCommission())
                .set("commissionRate", fresh.getCommissionRate())
                .set("totalDebited", fresh.getTotalDebited())
                .set("remainingAmount", fresh.getRemainingAmount())
                .set("description", fresh.getDescription())
                .set("initiatedBy", fresh.getInitiatedBy())
                .set("attempts", fresh.getAttempts())
                .set("reservedPayoutIds", new ArrayList<String>())
                .set("failureReason", null)
                .set("status", EscrowHoldStatus.PENDING)
                .set("pendingSince", now)
                .set("updatedAt", now);
        return mongo.updateFirst(Query.query(guard), update, EscrowHold.class).getModifiedCount() == 1;
    }

    @Override
    public boolean holdPendingToHeld(String id) {
        return moveHold(id, EscrowHoldStatus.PENDING, EscrowHoldStatus.HELD, null);
    }

    @Override
    public boolean holdPendingToFailed(String id, String reason) {
        return moveHold(id, EscrowHoldStatus.PENDING, EscrowHoldStatus.FAILED, reason);
    }

    private boolean moveHold(String id, EscrowHoldStatus from, EscrowHoldStatus to, String reason) {
        Criteria guard = Criteria.where("id").is(id).and("status").is(from);
        Update update = new Update()
                .set("status", to)
                .set("failureReason", reason)
                .set("updatedAt", LocalDateTime.now());
        return mongo.updateFirst(Query.query(guard), update, EscrowHold.class).getModifiedCount() == 1;
    }

    @Override
    public ReserveOutcome reserve(String holdId, String payoutId, BigDecimal amount) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            EscrowHold hold = mongo.findById(holdId, EscrowHold.class);
            if (hold == null) throw new ResourceNotFoundException("Escrow not found: " + holdId);
            if (hold.getReservedPayoutIds() != null && hold.getReservedPayoutIds().contains(payoutId)) {
                return ReserveOutcome.ALREADY;
            }
            if (hold.getStatus() != EscrowHoldStatus.HELD && hold.getStatus() != EscrowHoldStatus.PARTIALLY_PAID) {
                throw new HoldNotPayableException("Escrow hold " + hold.getReference() + " is " + hold.getStatus()
                        + " and cannot be paid out");
            }
            if (hold.getRemainingAmount().compareTo(amount) < 0) {
                throw new InsufficientFundsException("Payout of " + amount + " is more than the "
                        + hold.getRemainingAmount() + " left in escrow hold " + hold.getReference());
            }
            BigDecimal remaining = hold.getRemainingAmount().subtract(amount);

            Criteria guard = Criteria.where("id").is(holdId)
                    .and("status").is(hold.getStatus())
                    .and("remainingAmount").is(hold.getRemainingAmount())
                    .and("reservedPayoutIds").ne(payoutId);
            Update update = new Update()
                    .set("remainingAmount", remaining)
                    .set("status", EscrowStore.statusFor(remaining, hold.getAmount()))
                    .set("updatedAt", LocalDateTime.now())
                    .addToSet("reservedPayoutIds", payoutId);
            if (mongo.updateFirst(Query.query(guard), update, EscrowHold.class).getModifiedCount() == 1) {
                return ReserveOutcome.APPLIED;
            }
            // Another payout changed the hold between the read and the update; read again.
        }
        throw new IllegalStateException("The escrow hold kept changing; the payout was not reserved");
    }

    @Override
    public void unreserve(String holdId, String payoutId, BigDecimal amount) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            EscrowHold hold = mongo.findById(holdId, EscrowHold.class);
            if (hold == null || hold.getReservedPayoutIds() == null || !hold.getReservedPayoutIds().contains(payoutId)) {
                return;
            }
            BigDecimal remaining = hold.getRemainingAmount().add(amount);

            Criteria guard = Criteria.where("id").is(holdId)
                    .and("remainingAmount").is(hold.getRemainingAmount())
                    .and("reservedPayoutIds").is(payoutId);
            Update update = new Update()
                    .set("remainingAmount", remaining)
                    .set("status", EscrowStore.statusFor(remaining, hold.getAmount()))
                    .set("updatedAt", LocalDateTime.now())
                    .pull("reservedPayoutIds", payoutId);
            if (mongo.updateFirst(Query.query(guard), update, EscrowHold.class).getModifiedCount() == 1) {
                return;
            }
        }
        throw new IllegalStateException("The escrow hold kept changing; the payout reservation was not released");
    }

    @Override
    public List<EscrowHold> stalePendingHolds(LocalDateTime cutoff, int limit) {
        Query q = Query.query(Criteria.where("status").is(EscrowHoldStatus.PENDING).and("pendingSince").lt(cutoff))
                .with(Sort.by(Sort.Direction.ASC, "pendingSince")).limit(limit);
        return mongo.find(q, EscrowHold.class);
    }

    @Override
    public BigDecimal openHeldTotal() {
        Query q = Query.query(Criteria.where("status").in(EscrowHoldStatus.HELD, EscrowHoldStatus.PARTIALLY_PAID));
        BigDecimal total = BigDecimal.ZERO;
        for (EscrowHold hold : mongo.find(q, EscrowHold.class)) {
            if (hold.getRemainingAmount() != null) total = total.add(hold.getRemainingAmount());
        }
        return total;
    }

    // ----- payouts -----

    @Override
    public Optional<EscrowPayout> findPayout(String reference) {
        return Optional.ofNullable(mongo.findOne(Query.query(Criteria.where("reference").is(reference)), EscrowPayout.class));
    }

    @Override
    public Optional<EscrowPayout> findPayoutById(String id) {
        return Optional.ofNullable(mongo.findById(id, EscrowPayout.class));
    }

    @Override
    public List<EscrowPayout> payoutsOfHold(String holdId) {
        Query q = Query.query(Criteria.where("holdId").is(holdId)).with(Sort.by(Sort.Direction.ASC, "createdAt"));
        return mongo.find(q, EscrowPayout.class);
    }

    @Override
    public EscrowPayout insertPayout(EscrowPayout payout) {
        LocalDateTime now = LocalDateTime.now();
        if (payout.getCreatedAt() == null) payout.setCreatedAt(now);
        payout.setUpdatedAt(now);
        try {
            return mongo.insert(payout);
        } catch (DuplicateKeyException e) {
            throw new ReferenceTakenException(payout.getReference());
        }
    }

    @Override
    public boolean reopenPayout(String id, EscrowPayout fresh) {
        Criteria guard = Criteria.where("id").is(id).and("status").is(EscrowPayoutStatus.FAILED);
        LocalDateTime now = LocalDateTime.now();
        Update update = new Update()
                .set("recipientRole", fresh.getRecipientRole())
                .set("recipientUserId", fresh.getRecipientUserId())
                .set("recipientWalletId", fresh.getRecipientWalletId())
                .set("recipientEmail", fresh.getRecipientEmail())
                .set("amount", fresh.getAmount())
                .set("description", fresh.getDescription())
                .set("initiatedBy", fresh.getInitiatedBy())
                .set("attempts", fresh.getAttempts())
                .set("failureReason", null)
                .set("status", EscrowPayoutStatus.PENDING)
                .set("pendingSince", now)
                .set("updatedAt", now);
        return mongo.updateFirst(Query.query(guard), update, EscrowPayout.class).getModifiedCount() == 1;
    }

    @Override
    public boolean payoutPendingToSuccess(String id) {
        return movePayout(id, EscrowPayoutStatus.PENDING, EscrowPayoutStatus.SUCCESS, null);
    }

    @Override
    public boolean payoutPendingToFailed(String id, String reason) {
        return movePayout(id, EscrowPayoutStatus.PENDING, EscrowPayoutStatus.FAILED, reason);
    }

    private boolean movePayout(String id, EscrowPayoutStatus from, EscrowPayoutStatus to, String reason) {
        Criteria guard = Criteria.where("id").is(id).and("status").is(from);
        Update update = new Update()
                .set("status", to)
                .set("failureReason", reason)
                .set("updatedAt", LocalDateTime.now());
        return mongo.updateFirst(Query.query(guard), update, EscrowPayout.class).getModifiedCount() == 1;
    }

    @Override
    public List<EscrowPayout> stalePendingPayouts(LocalDateTime cutoff, int limit) {
        Query q = Query.query(Criteria.where("status").is(EscrowPayoutStatus.PENDING).and("pendingSince").lt(cutoff))
                .with(Sort.by(Sort.Direction.ASC, "pendingSince")).limit(limit);
        return mongo.find(q, EscrowPayout.class);
    }
}
