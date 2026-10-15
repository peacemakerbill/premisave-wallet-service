package com.premisave.wallet.commercial.rail.premisave;

import com.premisave.wallet.commercial.enums.ResultCode;
import com.premisave.wallet.commercial.transfer.PremisaveWalletCredit;
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
import java.util.Optional;

/**
 * Takes money out of a Premisave wallet for a commercial payment, exactly once. It mirrors
 * PremisaveWalletCredit: the balance is replaced only if it is still what was read and only if this payment
 * has not already been recorded on the wallet, then retried if someone else changed it first. So a repeat
 * after a crash never debits twice, and a debit can never push a balance below zero or overwrite a deposit
 * that landed in between.
 */
@Component("commercialPremisaveWalletDebit")
public class PremisaveWalletDebit {

    private static final Logger log = LoggerFactory.getLogger(PremisaveWalletDebit.class);
    private static final String DEBITS_FIELD = "commercialDebits";
    private static final int MAX_ATTEMPTS = 8;

    private final WalletRepository wallets;
    private final TransactionRepository transactions;
    private final WalletDebitMarkerRepository markers;
    private final MongoTemplate mongo;

    public PremisaveWalletDebit(WalletRepository wallets, TransactionRepository transactions,
                                WalletDebitMarkerRepository markers, MongoTemplate mongo) {
        this.wallets = wallets;
        this.transactions = transactions;
        this.markers = markers;
        this.mongo = mongo;
    }

    /** The wallet for an account (the user's email), or empty. */
    public Optional<Wallet> find(String account) {
        return account == null || account.isBlank() ? Optional.empty() : wallets.findByAccountNumber(ConsentRules.account(account));
    }

    /**
     * Debits usdCents for this transaction. Returns true when this call applied the debit and false when it
     * had already been applied. Throws PremisaveWalletCredit.Refused when the wallet is missing, frozen or
     * short of money.
     */
    public boolean debit(String account, long usdCents, String transactionId, String description) {
        if (markers.existsById(transactionId)) return false;
        BigDecimal amount = Money.fromCents(usdCents);

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Wallet wallet = find(account)
                    .orElseThrow(() -> new PremisaveWalletCredit.Refused(ResultCode.DECLINED, "The Premisave wallet does not exist"));
            if (alreadyOnWallet(wallet.getId(), transactionId)) {
                writeMarker(transactionId, wallet.getId(), usdCents);
                return false;
            }
            if (wallet.isFrozen()) {
                throw new PremisaveWalletCredit.Refused(ResultCode.DECLINED, "The Premisave wallet is frozen");
            }
            BigDecimal before = wallet.getBalance() == null ? BigDecimal.ZERO : wallet.getBalance();
            if (before.compareTo(amount) < 0) {
                throw new PremisaveWalletCredit.Refused(ResultCode.INSUFFICIENT_FUNDS, "The Premisave wallet balance is too low");
            }

            Criteria guard = Criteria.where("id").is(wallet.getId())
                    .and("balance").is(wallet.getBalance())
                    .and(DEBITS_FIELD + "." + transactionId).exists(false);
            Update update = new Update()
                    .set("balance", before.subtract(amount))
                    .set(DEBITS_FIELD + "." + transactionId, Instant.now().toString());
            if (mongo.updateFirst(Query.query(guard), update, Wallet.class).getModifiedCount() == 1) {
                record(wallet, amount, transactionId, description);
                writeMarker(transactionId, wallet.getId(), usdCents);
                return true;
            }
            // The balance changed between the read and the update; read again and retry.
        }
        throw new IllegalStateException("The wallet balance kept changing; the debit for " + transactionId + " was not applied");
    }

    private boolean alreadyOnWallet(String walletId, String transactionId) {
        Criteria c = Criteria.where("id").is(walletId).and(DEBITS_FIELD + "." + transactionId).exists(true);
        return mongo.count(Query.query(c), Wallet.class) > 0;
    }

    private void writeMarker(String transactionId, String walletId, long cents) {
        try {
            markers.insert(new WalletDebitMarker(transactionId, walletId, cents, Instant.now()));
        } catch (DuplicateKeyException already) {
            // Written by an earlier attempt.
        }
    }

    /** The row that shows the debit in the user's own wallet history. Failing to write it never undoes the debit. */
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
            log.error("Wallet {} was debited for {} but its history row could not be written", wallet.getId(), transactionId, e);
        }
    }
}
