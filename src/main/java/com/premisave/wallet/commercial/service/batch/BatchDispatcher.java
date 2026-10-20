package com.premisave.wallet.commercial.service.batch;

import com.premisave.wallet.commercial.config.properties.CommercialBatchProperties;
import com.premisave.wallet.commercial.dto.batch.BatchViews;
import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.entity.batch.PayoutBatch;
import com.premisave.wallet.commercial.enums.ResultCode;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.enums.TxType;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.rail.RailOutcome;
import com.premisave.wallet.commercial.repository.batch.PayoutBatchRepository;
import com.premisave.wallet.commercial.service.CommercialPayoutService;
import com.premisave.wallet.commercial.service.EventService;
import com.premisave.wallet.commercial.service.EventTypes;
import com.premisave.wallet.commercial.service.TransactionOutcomeService;
import com.premisave.wallet.commercial.util.Hashing;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.BasicUpdate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends queued bulk payouts to their rails at a steady pace, finishes batches whose payouts have all
 * ended, and cleans up after interruptions. Each run does four things in order:
 *
 * 1. sends up to itemsPerRun queued payouts, each claimed with a guarded update so two instances never send the same one
 * 2. closes payouts that were handed to a provider long ago and never answered (timed out, money released)
 * 3. rolls back batches whose intake was interrupted before they were saved
 * 4. marks batches with nothing left pending as completed and raises payout.batch.completed once
 */
@Component("commercialBatchDispatcher")
@ConditionalOnProperty(prefix = "commercial.batches", name = "dispatcher-enabled", havingValue = "true", matchIfMissing = true)
public class BatchDispatcher {

    private static final Logger log = LoggerFactory.getLogger(BatchDispatcher.class);
    private static final int INTAKE_STALE_MINUTES = 10;

    private final MongoTemplate mongo;
    private final PayoutBatchRepository batches;
    private final CommercialPayoutService payouts;
    private final BulkPayoutService bulk;
    private final TransactionOutcomeService outcomes;
    private final EventService events;
    private final CommercialBatchProperties props;

    public BatchDispatcher(MongoTemplate mongo, PayoutBatchRepository batches, CommercialPayoutService payouts,
                           BulkPayoutService bulk, TransactionOutcomeService outcomes,
                           EventService events, CommercialBatchProperties props) {
        this.mongo = mongo;
        this.batches = batches;
        this.payouts = payouts;
        this.bulk = bulk;
        this.outcomes = outcomes;
        this.events = events;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${commercial.batches.dispatch-interval-ms:2000}")
    public void run() {
        guarded("send queued payouts", this::sendQueued);
        guarded("close unanswered payouts", this::closeUnanswered);
        guarded("roll back interrupted intake", this::rollBackStalledIntake);
        guarded("complete batches", this::completeBatches);
    }

    private void guarded(String what, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            log.error("Bulk payout dispatcher could not {}", what, e);
        }
    }

    void sendQueued() {
        Criteria c = Criteria.where("type").is(TxType.PAYOUT.name())
                .and("status").in(TxStatus.OPEN.stream().map(Enum::name).toList())
                .and("relations." + BulkPayoutService.DISPATCH).is(BulkPayoutService.QUEUED);
        List<CommercialTransaction> due = mongo.find(Query.query(c)
                .with(PageRequest.of(0, props.itemsPerRun(), Sort.by(Sort.Direction.ASC, "createdAt"))), CommercialTransaction.class);
        for (CommercialTransaction candidate : due) {
            CommercialTransaction claimed = claim(candidate.getId());
            if (claimed == null) continue;
            try {
                payouts.dispatchQueued(claimed);
            } catch (CommercialException e) {
                // The provider was unreachable or refused; the payout is already closed as failed and its money released.
                log.warn("Bulk payout {} was not sent: {}", claimed.getId(), e.getMessage());
            } catch (RuntimeException e) {
                // Whether it reached the provider is unknown, so it stays SENDING and is closed by closeUnanswered if nothing arrives.
                log.error("Bulk payout {} may or may not have been sent", claimed.getId(), e);
                continue;
            }
            markSent(claimed.getId());
        }
    }

    private CommercialTransaction claim(String id) {
        Criteria c = Criteria.where("id").is(id).and("relations." + BulkPayoutService.DISPATCH).is(BulkPayoutService.QUEUED)
                .and("status").in(TxStatus.OPEN.stream().map(Enum::name).toList());
        Document set = new Document("relations." + BulkPayoutService.DISPATCH, BulkPayoutService.SENDING)
                .append("updatedAt", Date.from(Instant.now()));
        return mongo.findAndModify(Query.query(c), new BasicUpdate(new Document("$set", set)),
                FindAndModifyOptions.options().returnNew(true), CommercialTransaction.class);
    }

    private void markSent(String id) {
        Criteria c = Criteria.where("id").is(id).and("relations." + BulkPayoutService.DISPATCH).is(BulkPayoutService.SENDING);
        mongo.updateFirst(Query.query(c), new BasicUpdate(new Document("$set",
                new Document("relations." + BulkPayoutService.DISPATCH, BulkPayoutService.SENT))), CommercialTransaction.class);
    }

    /**
     * A payout claimed for sending whose send never finished. If the provider had received it, its result would
     * have arrived by now, so after stuckAfterMinutes it is closed as timed out and the money released.
     */
    void closeUnanswered() {
        Instant cutoff = Instant.now().minusSeconds(props.stuckAfterMinutes() * 60L);
        Criteria c = Criteria.where("type").is(TxType.PAYOUT.name())
                .and("status").in(TxStatus.OPEN.stream().map(Enum::name).toList())
                .and("relations." + BulkPayoutService.DISPATCH).is(BulkPayoutService.SENDING)
                .and("updatedAt").lt(cutoff);
        for (CommercialTransaction tx : mongo.find(Query.query(c).with(PageRequest.of(0, 100)), CommercialTransaction.class)) {
            log.warn("Bulk payout {} had no answer for {} minutes; closing it as timed out", tx.getId(), props.stuckAfterMinutes());
            outcomes.apply(tx, RailOutcome.of(TxStatus.TIMED_OUT, ResultCode.TIMED_OUT,
                    "No answer from the provider; check the provider statement before sending again"));
        }
    }

    void rollBackStalledIntake() {
        Instant cutoff = Instant.now().minusSeconds(INTAKE_STALE_MINUTES * 60L);
        Criteria c = Criteria.where("type").is(TxType.PAYOUT.name())
                .and("status").in(TxStatus.OPEN.stream().map(Enum::name).toList())
                .and("relations." + BulkPayoutService.DISPATCH).is(BulkPayoutService.INTAKE)
                .and("updatedAt").lt(cutoff);
        List<CommercialTransaction> stale = mongo.find(Query.query(c).with(PageRequest.of(0, 500)), CommercialTransaction.class);
        Map<String, List<CommercialTransaction>> byBatch = new LinkedHashMap<>();
        for (CommercialTransaction tx : stale) {
            byBatch.computeIfAbsent(tx.getRelations().get("batchId"), k -> new java.util.ArrayList<>()).add(tx);
        }
        byBatch.forEach((batchId, items) -> {
            log.warn("Rolling back {} payouts of batch {} whose intake was interrupted", items.size(), batchId);
            bulk.rollback(items, "The batch was interrupted before it was accepted");
            batches.findById(batchId).ifPresent(b -> {
                if (b.getStatus() == PayoutBatch.Status.PROCESSING) {
                    b.setStatus(PayoutBatch.Status.REJECTED);
                    b.setUniqueKey(null);
                    b.setAcceptedCount(0);
                    b.setTotalAmountCents(0);
                    b.setTotalFeeCents(0);
                    b.setEventEmitted(true);
                    b.setRejected(List.of(new PayoutBatch.Rejection("*", "INTAKE_INTERRUPTED",
                            "The request was interrupted before it was accepted; no money was moved. Submit it again")));
                    b.setCompletedAt(Instant.now());
                    b.setUpdatedAt(Instant.now());
                    batches.save(b);
                }
            });
        });
    }

    void completeBatches() {
        // Batches with nothing pending.
        List<PayoutBatch> processing = mongo.find(Query.query(Criteria.where("status").is(PayoutBatch.Status.PROCESSING.name()))
                .with(PageRequest.of(0, 50, Sort.by(Sort.Direction.ASC, "updatedAt"))), PayoutBatch.class);
        for (PayoutBatch b : processing) {
            BatchRules.Counters counters = bulk.counters(b.getBatchId());
            if (!BatchRules.isFinished(counters)) continue;
            PayoutBatch.Status to = BatchRules.finalStatus(counters, b.getRejected().size());
            Document set = new Document("status", to.name()).append("completedAt", Date.from(Instant.now()))
                    .append("updatedAt", Date.from(Instant.now())).append("eventEmitted", false);
            PayoutBatch done = mongo.findAndModify(
                    Query.query(Criteria.where("batchId").is(b.getBatchId()).and("status").is(PayoutBatch.Status.PROCESSING.name())),
                    new BasicUpdate(new Document("$set", set)), FindAndModifyOptions.options().returnNew(true), PayoutBatch.class);
            if (done != null) announce(done, counters);
        }
        // Batches that finished but whose event was not raised (the process stopped in between).
        Criteria unannounced = Criteria.where("eventEmitted").is(false)
                .and("status").in(List.of(PayoutBatch.Status.COMPLETED.name(), PayoutBatch.Status.COMPLETED_WITH_FAILURES.name()));
        for (PayoutBatch b : mongo.find(Query.query(unannounced).with(PageRequest.of(0, 50)), PayoutBatch.class)) {
            announce(b, bulk.counters(b.getBatchId()));
        }
    }

    private void announce(PayoutBatch b, BatchRules.Counters counters) {
        String id = "evt_" + Hashing.sha256Hex((b.getBatchId() + "|" + EventTypes.PAYOUT_BATCH_COMPLETED).getBytes(StandardCharsets.UTF_8)).substring(0, 16);
        events.emitWithId(id, EventTypes.PAYOUT_BATCH_COMPLETED, b.getMerchantId(), b.getAppId(), b.isLivemode(), b.getBatchId(),
                BatchViews.detail(b, counters),
                events.targetsFor(b.getAppId(), b.isLivemode(), EventTypes.PAYOUT_BATCH_COMPLETED, b.getCallbackUrl(), false));
        mongo.updateFirst(Query.query(Criteria.where("batchId").is(b.getBatchId())),
                new BasicUpdate(new Document("$set", new Document("eventEmitted", true))), PayoutBatch.class);
        log.info("Batch {} is {}", b.getBatchId(), b.getStatus());
    }
}
