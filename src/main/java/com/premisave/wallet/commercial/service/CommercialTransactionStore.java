package com.premisave.wallet.commercial.service;

import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.entity.TimelineEntry;
import com.premisave.wallet.commercial.entity.TxResult;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.repository.CommercialTransactionRepository;
import org.bson.Document;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.BasicUpdate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * All status changes on a transaction go through here. A change names the
 * statuses it may leave, and the update only applies when the transaction is
 * still in one of them, so when two things race (a webhook and a poll, say)
 * exactly one of them wins and the loser sees an empty result.
 */
@Component
public class CommercialTransactionStore {

    private final MongoTemplate mongo;
    private final CommercialTransactionRepository repo;

    public CommercialTransactionStore(MongoTemplate mongo, CommercialTransactionRepository repo) {
        this.mongo = mongo;
        this.repo = repo;
    }

    public CommercialTransaction insert(CommercialTransaction tx) {
        Instant now = Instant.now();
        tx.setCreatedAt(now);
        tx.setUpdatedAt(now);
        tx.getTimeline().add(new TimelineEntry(now, tx.getStatus(), "Created"));
        return repo.insert(tx);
    }

    /** Removes a transaction that was recorded but never sent anywhere (no rail call, no money moved, no event). */
    public void discardUnsent(String id) {
        repo.deleteById(id);
    }

    public Optional<CommercialTransaction> find(String id) {
        return repo.findById(id);
    }

    /**
     * Moves a transaction from one of {@code from} to {@code to}. Returns the updated
     * document, or empty when it was no longer in an allowed status. Reaching a final
     * status marks the money side as owed (ledgerApplied false) until it is applied.
     */
    public Optional<CommercialTransaction> transition(String id, Set<TxStatus> from, TxStatus to, TxResult result,
                                                      String note, Map<String, Object> extraSet) {
        Instant now = Instant.now();
        Document set = new Document("status", to.name()).append("updatedAt", Date.from(now));
        if (to.isFinal()) {
            set.append("completedAt", Date.from(now));
            set.append("ledgerApplied", false);
            set.append("nextAction", new Document("type", "NONE"));
        }
        if (result != null) {
            set.append("result", new Document("code", result.getCode().name())
                    .append("description", result.getDescription())
                    .append("providerCode", result.getProviderCode())
                    .append("providerDescription", result.getProviderDescription()));
        }
        if (extraSet != null) extraSet.forEach(set::append);

        Document entry = new Document("at", Date.from(now)).append("status", to.name()).append("note", note);
        Document update = new Document("$set", set)
                .append("$inc", new Document("version", 1L))
                .append("$push", new Document("timeline", entry));

        Criteria criteria = Criteria.where("id").is(id).and("status").in(from.stream().map(Enum::name).toList());
        return Optional.ofNullable(mongo.findAndModify(Query.query(criteria), new BasicUpdate(update),
                FindAndModifyOptions.options().returnNew(true), CommercialTransaction.class));
    }

    /** Records the money side of a final status as done. Only the first caller gets true. */
    public boolean markLedgerApplied(String id) {
        Criteria criteria = Criteria.where("id").is(id).and("ledgerApplied").is(false);
        CommercialTransaction updated = mongo.findAndModify(Query.query(criteria),
                new BasicUpdate(new Document("$set", new Document("ledgerApplied", true))),
                FindAndModifyOptions.options().returnNew(true), CommercialTransaction.class);
        return updated != null;
    }
}
