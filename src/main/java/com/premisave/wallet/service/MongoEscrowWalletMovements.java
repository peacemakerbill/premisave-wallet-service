package com.premisave.wallet.service;

import com.premisave.wallet.entity.EscrowWalletMarker;
import com.premisave.wallet.entity.Transaction;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.TransactionStatus;
import com.premisave.wallet.enums.TransactionType;
import com.premisave.wallet.exception.InsufficientFundsException;
import com.premisave.wallet.exception.WalletFrozenException;
import com.premisave.wallet.exception.WalletNotFoundException;
import com.premisave.wallet.repository.TransactionRepository;
import com.premisave.wallet.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Changes a wallet balance for escrow, exactly once per movement key, in the same way the commercial module does
 * (PremisaveWalletDebit / PremisaveWalletCredit): the existing wallet code reads a balance, changes it and saves
 * it back, so this replaces the balance only if it is still what was read, and records the movement key on the
 * wallet document in the same update. If someone else changed the balance in between, it reads again and retries.
 *
 * Two fields on the wallet document carry the proof: escrowOps.&lt;key&gt; (the balance was changed) and
 * escrowCancelled.&lt;key&gt; (the movement was ruled out, see cancel()). A guarded update never writes one when
 * the other exists, so a key is applied or cancelled, never both. A marker document repeats the same fact in a
 * separate collection, because the older wallet code saves the whole wallet back and would drop a field it does
 * not know about.
 */
@Component
public class MongoEscrowWalletMovements implements EscrowWalletMovements {

    private static final Logger log = LoggerFactory.getLogger(MongoEscrowWalletMovements.class);
    private static final String APPLIED_FIELD = "escrowOps";
    private static final String CANCELLED_FIELD = "escrowCancelled";
    private static final String APPLIED = "APPLIED";
    private static final String CANCELLED = "CANCELLED";
    private static final int MAX_ATTEMPTS = 8;

    private final WalletRepository wallets;
    private final TransactionRepository transactions;
    private final MongoTemplate mongo;

    public MongoEscrowWalletMovements(WalletRepository wallets, TransactionRepository transactions, MongoTemplate mongo) {
        this.wallets = wallets;
        this.transactions = transactions;
        this.mongo = mongo;
    }

    @Override
    public boolean debit(String walletId, String key, BigDecimal amount, String reference, String description) {
        return apply(false, walletId, key, amount, reference, description);
    }

    @Override
    public boolean credit(String walletId, String key, BigDecimal amount, String reference, String description) {
        return apply(true, walletId, key, amount, reference, description);
    }

    private boolean apply(boolean credit, String walletId, String key, BigDecimal amount, String reference, String description) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            EscrowWalletMarker marker = mongo.findById(key, EscrowWalletMarker.class);
            if (marker != null) {
                if (CANCELLED.equals(marker.getKind())) throw cancelled(key);
                return false;
            }
            Wallet wallet = wallets.findById(walletId)
                    .orElseThrow(() -> new WalletNotFoundException("Wallet not found: " + walletId));
            if (onWallet(walletId, CANCELLED_FIELD, key)) {
                writeMarker(key, walletId, CANCELLED, amount);
                throw cancelled(key);
            }
            if (onWallet(walletId, APPLIED_FIELD, key)) {
                writeMarker(key, walletId, APPLIED, amount);
                return false;
            }
            if (wallet.isFrozen()) {
                throw new WalletFrozenException(credit
                        ? "Recipient wallet is currently frozen and cannot receive funds"
                        : "Wallet is frozen and cannot perform transactions until it is unfrozen");
            }
            BigDecimal before = wallet.getBalance() == null ? BigDecimal.ZERO : wallet.getBalance();
            if (!credit && before.compareTo(amount) < 0) {
                throw new InsufficientFundsException("Insufficient balance. Available: " + before);
            }
            BigDecimal after = credit ? before.add(amount) : before.subtract(amount);

            Criteria guard = Criteria.where("id").is(walletId)
                    .and("balance").is(wallet.getBalance())
                    .and(APPLIED_FIELD + "." + key).exists(false)
                    .and(CANCELLED_FIELD + "." + key).exists(false);
            Update update = new Update()
                    .set("balance", after)
                    .set(APPLIED_FIELD + "." + key, Instant.now().toString());
            if (mongo.updateFirst(Query.query(guard), update, Wallet.class).getModifiedCount() == 1) {
                writeHistory(wallet, credit ? amount : amount.negate(), reference, description);
                writeMarker(key, walletId, APPLIED, amount);
                return true;
            }
            // The balance changed between the read and the update, or the key was just cancelled; read again.
        }
        throw new IllegalStateException("The wallet balance kept changing; the escrow movement " + key + " was not applied");
    }

    @Override
    public boolean cancel(String walletId, String key) {
        EscrowWalletMarker marker = mongo.findById(key, EscrowWalletMarker.class);
        if (marker != null) return CANCELLED.equals(marker.getKind());

        Criteria guard = Criteria.where("id").is(walletId)
                .and(APPLIED_FIELD + "." + key).exists(false)
                .and(CANCELLED_FIELD + "." + key).exists(false);
        Update update = new Update().set(CANCELLED_FIELD + "." + key, Instant.now().toString());
        if (mongo.updateFirst(Query.query(guard), update, Wallet.class).getModifiedCount() == 1) {
            writeMarker(key, walletId, CANCELLED, null);
            return true;
        }
        if (onWallet(walletId, CANCELLED_FIELD, key)) {
            writeMarker(key, walletId, CANCELLED, null);
            return true;
        }
        if (onWallet(walletId, APPLIED_FIELD, key)) {
            writeMarker(key, walletId, APPLIED, null);
            return false;
        }
        // No such wallet document, so nothing can ever be applied to it.
        return true;
    }

    @Override
    public void ensureHistory(String walletId, BigDecimal signedAmount, String reference, String description) {
        if (transactions.existsByReference(reference)) return;
        wallets.findById(walletId).ifPresent(wallet -> writeHistory(wallet, signedAmount, reference, description));
    }

    private boolean onWallet(String walletId, String field, String key) {
        Criteria c = Criteria.where("id").is(walletId).and(field + "." + key).exists(true);
        return mongo.count(Query.query(c), Wallet.class) > 0;
    }

    private void writeMarker(String key, String walletId, String kind, BigDecimal amount) {
        try {
            EscrowWalletMarker marker = new EscrowWalletMarker();
            marker.setId(key);
            marker.setWalletId(walletId);
            marker.setKind(kind);
            marker.setAmount(amount);
            marker.setCreatedAt(LocalDateTime.now());
            mongo.insert(marker);
        } catch (DuplicateKeyException already) {
            // Written by an earlier attempt.
        }
    }

    /** The row that shows the movement in the user's own statement. Failing to write it never undoes the movement. */
    private void writeHistory(Wallet wallet, BigDecimal signedAmount, String reference, String description) {
        try {
            Transaction row = new Transaction();
            row.setUserId(wallet.getUserId());
            row.setWalletId(wallet.getId());
            row.setType(TransactionType.TRANSFER);
            row.setStatus(TransactionStatus.COMPLETED);
            row.setAmount(signedAmount);
            row.setCurrency(Currency.USD);
            row.setDescription(description);
            row.setReference(reference);
            transactions.save(row);
        } catch (RuntimeException e) {
            log.error("Wallet {} was changed for escrow reference {} but its statement row could not be written",
                    wallet.getId(), reference, e);
        }
    }

    private static IllegalStateException cancelled(String key) {
        return new IllegalStateException("This escrow step was cancelled by the recovery sweep and nothing moved ("
                + key + "). Look the reference up and retry it.");
    }
}
