package com.premisave.wallet.commercial.service;

import com.premisave.wallet.commercial.entity.LedgerLine;
import com.premisave.wallet.commercial.entity.MerchantWallet;
import com.premisave.wallet.commercial.enums.Environment;
import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.enums.LedgerEntryType;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.repository.LedgerLineRepository;
import com.premisave.wallet.commercial.repository.MerchantWalletRepository;
import com.premisave.wallet.commercial.util.Ids;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.BasicUpdate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * The only place a merchant balance changes. Each call is one atomic
 * update of the wallet document, guarded so the balance cannot go negative,
 * and tagged with an operation key so a retry cannot apply it twice. A ledger
 * line recording the result follows each update.
 *
 * Balances are in USD cents: available (spendable), held (reserved for
 * in-flight payouts) and pending (received, not yet released).
 */
@Service
public class LedgerService {

    /** How many recent operation keys each wallet remembers. Retries happen within seconds, far inside this window. */
    private static final int OP_KEY_WINDOW = 1000;

    public record Applied(MerchantWallet wallet, boolean alreadyApplied) {}

    private final MongoTemplate mongo;
    private final MerchantWalletRepository wallets;
    private final LedgerLineRepository lines;

    public LedgerService(MongoTemplate mongo, MerchantWalletRepository wallets, LedgerLineRepository lines) {
        this.mongo = mongo;
        this.wallets = wallets;
        this.lines = lines;
    }

    public MerchantWallet createWallet(String merchantId, Environment env) {
        MerchantWallet w = new MerchantWallet(Ids.next("wal"), merchantId, env, Instant.now());
        return wallets.insert(w);
    }

    public MerchantWallet wallet(String walletId) {
        return wallets.findById(walletId)
                .orElseThrow(() -> new CommercialException(ErrorCode.NOT_FOUND, "Wallet not found"));
    }

    /** Collection received: money lands in the available balance, net of the fee. */
    public Applied credit(String walletId, String transactionId, long netCents, long feeCents, String description) {
        return apply(walletId, transactionId + ":CREDIT", netCents, 0, 0, LedgerEntryType.COLLECTION_CREDIT,
                transactionId, description, feeCents);
    }

    /** Payout started: amount plus fee moves from available to held. Fails with INSUFFICIENT_BALANCE when it cannot. */
    public Applied hold(String walletId, String transactionId, long totalCents, String description) {
        return apply(walletId, transactionId + ":HOLD", -totalCents, totalCents, 0, LedgerEntryType.PAYOUT_HOLD,
                transactionId, description, 0);
    }

    /** Payout did not go through: the held money returns to available. */
    public Applied release(String walletId, String transactionId, long totalCents, String description) {
        return apply(walletId, transactionId + ":RELEASE", totalCents, -totalCents, 0,
                LedgerEntryType.PAYOUT_HOLD_RELEASE, transactionId, description, 0);
    }

    /** Payout succeeded: the held money is spent. */
    public Applied capture(String walletId, String transactionId, long totalCents, long feeCents, String description) {
        return apply(walletId, transactionId + ":CAPTURE", 0, -totalCents, 0, LedgerEntryType.PAYOUT_DEBIT,
                transactionId, description, feeCents);
    }

    /** A succeeded payout was later reversed by the provider: the amount (not the fee) returns to available. */
    public Applied reverse(String walletId, String transactionId, long amountCents, String description) {
        return apply(walletId, transactionId + ":REVERSAL", amountCents, 0, 0, LedgerEntryType.ADJUSTMENT,
                transactionId, description, 0);
    }

    /** Sandbox only: adds test money. */
    public Applied fund(String walletId, long cents, String description) {
        String id = Ids.next("fund");
        return apply(walletId, id + ":FUND", cents, 0, 0, LedgerEntryType.TEST_FUNDING, id, description, 0);
    }

    /** Sandbox only: zero the wallet. */
    public void resetWallet(String walletId) {
        Document set = new Document("availableCents", 0L).append("heldCents", 0L).append("pendingCents", 0L)
                .append("appliedOps", List.of()).append("updatedAt", Date.from(Instant.now()));
        mongo.findAndModify(Query.query(Criteria.where("walletId").is(walletId)),
                new BasicUpdate(new Document("$set", set)),
                FindAndModifyOptions.options().returnNew(true), MerchantWallet.class);
    }

    Applied apply(String walletId, String opKey, long availableDelta, long heldDelta, long pendingDelta,
                  LedgerEntryType type, String objectId, String description, long feeCents) {
        Criteria criteria = Criteria.where("walletId").is(walletId).and("appliedOps").ne(opKey);
        // The balance guards live in the same query as the update, so the check and the change are one atomic step.
        if (availableDelta < 0) criteria = criteria.and("availableCents").gte(-availableDelta);
        if (heldDelta < 0) criteria = criteria.and("heldCents").gte(-heldDelta);
        if (pendingDelta < 0) criteria = criteria.and("pendingCents").gte(-pendingDelta);

        Document inc = new Document();
        if (availableDelta != 0) inc.append("availableCents", availableDelta);
        if (heldDelta != 0) inc.append("heldCents", heldDelta);
        if (pendingDelta != 0) inc.append("pendingCents", pendingDelta);

        Instant now = Instant.now();
        Document update = new Document("$set", new Document("updatedAt", Date.from(now)))
                .append("$push", new Document("appliedOps",
                        new Document("$each", List.of(opKey)).append("$slice", -OP_KEY_WINDOW)));
        if (!inc.isEmpty()) update.append("$inc", inc);

        MerchantWallet after = mongo.findAndModify(Query.query(criteria), new BasicUpdate(update),
                FindAndModifyOptions.options().returnNew(true), MerchantWallet.class);

        if (after == null) {
            MerchantWallet current = wallet(walletId);
            if (current.getAppliedOps().contains(opKey)) {
                insertLine(current, type, objectId, description, availableDelta, heldDelta, pendingDelta, feeCents, now);
                return new Applied(current, true);
            }
            if (availableDelta < 0) {
                throw new CommercialException(ErrorCode.INSUFFICIENT_BALANCE, "Insufficient available balance")
                        .with("availableCents", current.getAvailableCents());
            }
            throw new CommercialException(ErrorCode.INVALID_STATE, "Wallet balances do not allow this operation");
        }

        insertLine(after, type, objectId, description, availableDelta, heldDelta, pendingDelta, feeCents, now);
        return new Applied(after, false);
    }

    private void insertLine(MerchantWallet wallet, LedgerEntryType type, String objectId, String description,
                            long availableDelta, long heldDelta, long pendingDelta, long feeCents, Instant now) {
        try {
            lines.insert(new LedgerLine(Ids.next("led"), wallet, type, objectId, description,
                    availableDelta, heldDelta, pendingDelta, feeCents, now));
        } catch (DuplicateKeyException alreadyWritten) {
            // The line for this operation exists from an earlier attempt.
        }
    }
}
