package com.premisave.wallet.commercial.transfer;

import com.premisave.wallet.commercial.enums.ResultCode;
import com.premisave.wallet.commercial.util.Money;
import com.premisave.wallet.entity.Transaction;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.TransactionStatus;
import com.premisave.wallet.enums.TransactionType;
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
import java.util.Locale;
import java.util.Optional;

/**
 * Puts money into a Premisave user's wallet on behalf of a merchant transfer.
 *
 * The existing wallet code changes a balance by reading it, adding to it and
 * saving. This credit instead replaces the balance only if it is still what
 * was read (and only if this transaction has not been recorded on the wallet),
 * and retries when someone else changed it first. So a credit is applied
 * exactly once even if the caller repeats it after a crash, and it cannot
 * overwrite a deposit or transfer that landed in between.
 *
 * Two records prove a credit was made: a field on the wallet document written
 * in the same update as the balance, and a marker document. The wallet field
 * is the strict guard; the marker covers a later full save of the wallet by
 * the existing code, which does not know about the field.
 */
@Component("commercialPremisaveWalletCredit")
public class PremisaveWalletCredit {

    private static final Logger log = LoggerFactory.getLogger(PremisaveWalletCredit.class);
    private static final String CREDITS_FIELD = "commercialCredits";
    private static final int MAX_ATTEMPTS = 8;

    /** The recipient cannot be credited; the code says why in provider neutral terms. */
    public static class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final ResultCode code;

        public Refused(ResultCode code, String message) {
            super(message);
            this.code = code;
        }

        public ResultCode code() { return code; }
    }

    public record Recipient(String walletId, String userId, String accountNumber, boolean frozen) {}

    private final WalletRepository wallets;
    private final TransactionRepository transactions;
    private final WalletCreditMarkerRepository markers;
    private final MongoTemplate mongo;

    public PremisaveWalletCredit(WalletRepository wallets, TransactionRepository transactions,
                                 WalletCreditMarkerRepository markers, MongoTemplate mongo) {
        this.wallets = wallets;
        this.transactions = transactions;
        this.markers = markers;
        this.mongo = mongo;
    }

    /** The wallet an account reference (the user's email) points at, or empty. */
    public Optional<Recipient> find(String accountRef) {
        return lookup(accountRef).map(PremisaveWalletCredit::recipient);
    }

    /**
     * Credits usdCents to the wallet for this transaction. Returns true when this call applied
     * the credit and false when it had already been applied. Throws Refused when the wallet does
     * not exist or is frozen.
     */
    public boolean credit(String accountRef, long usdCents, String transactionId, String description) {
        if (markers.existsById(transactionId)) return false;
        BigDecimal amount = Money.fromCents(usdCents);

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Wallet wallet = lookup(accountRef)
                    .orElseThrow(() -> new Refused(ResultCode.INVALID_DESTINATION, "The recipient wallet does not exist"));
            if (wallet.isFrozen()) {
                throw new Refused(ResultCode.DECLINED, "The recipient wallet is frozen and cannot receive funds");
            }
            if (alreadyOnWallet(wallet.getId(), transactionId)) {
                writeMarker(transactionId, wallet.getId(), usdCents);
                return false;
            }

            BigDecimal before = wallet.getBalance() == null ? BigDecimal.ZERO : wallet.getBalance();
            Criteria guard = Criteria.where("id").is(wallet.getId())
                    .and("balance").is(wallet.getBalance())
                    .and(CREDITS_FIELD + "." + transactionId).exists(false);
            Update update = new Update()
                    .set("balance", before.add(amount))
                    .set(CREDITS_FIELD + "." + transactionId, Instant.now().toString());
            if (mongo.updateFirst(Query.query(guard), update, Wallet.class).getModifiedCount() == 1) {
                record(wallet, amount, transactionId, description);
                writeMarker(transactionId, wallet.getId(), usdCents);
                return true;
            }
            // Someone changed the balance between the read and the update; read again and retry.
        }
        throw new IllegalStateException("The wallet balance kept changing; the credit for " + transactionId + " was not applied");
    }

    private Optional<Wallet> lookup(String accountRef) {
        if (accountRef == null || accountRef.isBlank()) return Optional.empty();
        String ref = accountRef.trim();
        Optional<Wallet> exact = wallets.findByAccountNumber(ref);
        if (exact.isPresent()) return exact;
        String lower = ref.toLowerCase(Locale.ROOT);
        return lower.equals(ref) ? Optional.empty() : wallets.findByAccountNumber(lower);
    }

    private boolean alreadyOnWallet(String walletId, String transactionId) {
        Criteria c = Criteria.where("id").is(walletId).and(CREDITS_FIELD + "." + transactionId).exists(true);
        return mongo.count(Query.query(c), Wallet.class) > 0;
    }

    private void writeMarker(String transactionId, String walletId, long cents) {
        try {
            markers.insert(new WalletCreditMarker(transactionId, walletId, cents, Instant.now()));
        } catch (DuplicateKeyException already) {
            // Written by an earlier attempt.
        }
    }

    /** The row that shows the credit in the user's own wallet history. Failing to write it never undoes the credit. */
    private void record(Wallet wallet, BigDecimal amount, String transactionId, String description) {
        try {
            Transaction row = new Transaction();
            row.setUserId(wallet.getUserId());
            row.setWalletId(wallet.getId());
            row.setType(TransactionType.TRANSFER);
            row.setStatus(TransactionStatus.COMPLETED);
            row.setAmount(amount);
            row.setCurrency(Currency.USD);
            row.setDescription(description);
            row.setReference(transactionId);
            transactions.save(row);
        } catch (RuntimeException e) {
            log.error("Wallet {} was credited for {} but its history row could not be written", wallet.getId(), transactionId, e);
        }
    }

    private static Recipient recipient(Wallet w) {
        return new Recipient(w.getId(), w.getUserId(), w.getAccountNumber(), w.isFrozen());
    }
}
