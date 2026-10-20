package com.premisave.wallet.commercial.service.batch;

import com.premisave.wallet.commercial.config.properties.CommercialBatchProperties;
import com.premisave.wallet.commercial.dto.batch.BatchViews;
import com.premisave.wallet.commercial.dto.batch.BulkPayoutItem;
import com.premisave.wallet.commercial.dto.batch.BulkPayoutRequest;
import com.premisave.wallet.commercial.dto.payment.CreatePayoutRequest;
import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.entity.batch.PayoutBatch;
import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.enums.ResultCode;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.enums.TxType;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.rail.RailOutcome;
import com.premisave.wallet.commercial.repository.batch.PayoutBatchRepository;
import com.premisave.wallet.commercial.security.CommercialContext;
import com.premisave.wallet.commercial.service.CommercialIdempotencyService;
import com.premisave.wallet.commercial.service.CommercialPaymentService;
import com.premisave.wallet.commercial.service.CommercialPayoutService;
import com.premisave.wallet.commercial.service.CommercialTransactionStore;
import com.premisave.wallet.commercial.service.LedgerService;
import com.premisave.wallet.commercial.service.TransactionOutcomeService;
import com.premisave.wallet.commercial.util.Ids;
import com.premisave.wallet.commercial.util.Money;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.BasicUpdate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;

/**
 * Bulk payouts (sections 6.2 and 6.5).
 *
 * Intake checks every item first without moving money, then compares the total (amounts plus fees) with
 * the available balance, then holds and records the items one by one so each counts against the limits
 * of the next. Recorded items wait in the INTAKE state until the whole request is saved; only then are they
 * released to QUEUED, and BatchDispatcher sends them at a controlled rate. That ordering means an
 * ALL_OR_NOTHING batch that fails half way can be rolled back without any item having been sent.
 */
@Service("commercialBulkPayoutService")
public class BulkPayoutService {

    private static final Logger log = LoggerFactory.getLogger(BulkPayoutService.class);

    public static final String DISPATCH = "dispatch";
    public static final String INTAKE = "INTAKE";
    public static final String QUEUED = "QUEUED";
    public static final String SENDING = "SENDING";
    public static final String SENT = "SENT";
    static final String BATCH_REJECTED = "BATCH_REJECTED";

    private final CommercialIdempotencyService idempotency;
    private final CommercialPayoutService payouts;
    private final PayoutBatchRepository batches;
    private final LedgerService ledger;
    private final CommercialTransactionStore store;
    private final TransactionOutcomeService outcomes;
    private final MongoTemplate mongo;
    private final CommercialBatchProperties props;

    public BulkPayoutService(CommercialIdempotencyService idempotency, CommercialPayoutService payouts, PayoutBatchRepository batches,
                             LedgerService ledger, CommercialTransactionStore store, TransactionOutcomeService outcomes,
                             MongoTemplate mongo, CommercialBatchProperties props) {
        this.idempotency = idempotency;
        this.payouts = payouts;
        this.batches = batches;
        this.ledger = ledger;
        this.store = store;
        this.outcomes = outcomes;
        this.mongo = mongo;
        this.props = props;
    }

    public CommercialIdempotencyService.Outcome<Map<String, Object>> create(CommercialContext ctx, BulkPayoutRequest req,
                                                                   String idempotencyKey, String bodyHash) {
        return idempotency.execute(ctx, idempotencyKey, bodyHash, "PAYOUT_BATCH",
                () -> doCreate(ctx, req),
                id -> {
                    PayoutBatch b = requireOwn(ctx, id);
                    return BatchViews.created(b, itemsOf(b.getBatchId()));
                });
    }

    private record Planned(int index, CreatePayoutRequest request, CommercialPayoutService.Prepared prepared) {}

    private String doCreate(CommercialContext ctx, BulkPayoutRequest req) {
        if (req.items().size() > props.maxItems()) {
            throw CommercialException.validation("items", "allows at most " + props.maxItems() + " items");
        }
        CommercialPaymentService.checkCallbackUrl(ctx, req.callbackUrl());

        Instant now = Instant.now();
        PayoutBatch b = new PayoutBatch();
        b.setBatchId(Ids.next("bat"));
        b.setUniqueKey(PayoutBatch.uniqueKeyFor(ctx.merchantId(), ctx.livemode(), req.merchantReference().trim()));
        b.setMerchantId(ctx.merchantId());
        b.setAppId(ctx.appId());
        b.setLivemode(ctx.livemode());
        b.setMerchantReference(req.merchantReference().trim());
        b.setMode(req.mode());
        b.setCallbackUrl(req.callbackUrl() == null || req.callbackUrl().isBlank() ? null : req.callbackUrl().trim());
        b.setRequestedCount(req.items().size());
        b.setCreatedAt(now);
        b.setUpdatedAt(now);
        try {
            batches.insert(b);
        } catch (DuplicateKeyException e) {
            throw new CommercialException(ErrorCode.DUPLICATE_REQUEST,
                    "A batch with merchantReference " + b.getMerchantReference() + " already exists");
        }

        List<CommercialTransaction> queued = new ArrayList<>();
        try {
            run(ctx, req, b, queued);
        } catch (RuntimeException e) {
            // Nothing was sent (items only leave INTAKE once the batch is saved), so undo it all.
            rollback(queued, "The batch could not be accepted");
            batches.deleteById(b.getBatchId());
            throw e;
        }
        return b.getBatchId();
    }

    private void run(CommercialContext ctx, BulkPayoutRequest req, PayoutBatch b, List<CommercialTransaction> queued) {
        boolean allOrNothing = req.mode() == PayoutBatch.Mode.ALL_OR_NOTHING;
        List<PayoutBatch.Rejection> rejections = new ArrayList<>();
        List<Planned> planned = new ArrayList<>();

        // Pass 1: everything that can be decided without moving money.
        Set<String> seen = new HashSet<>();
        int index = 0;
        for (BulkPayoutItem item : req.items()) {
            index++;
            String ref = item.merchantReference().trim();
            try {
                if (!seen.add(ref)) {
                    throw new CommercialException(ErrorCode.DUPLICATE_REQUEST, "merchantReference is repeated in this batch");
                }
                CreatePayoutRequest one = item.toRequest();
                planned.add(new Planned(index, one, payouts.prepare(ctx, one, false)));
            } catch (CommercialException e) {
                rejections.add(new PayoutBatch.Rejection(ref, e.code().name(), e.getMessage()));
            }
        }
        if (allOrNothing && !rejections.isEmpty()) {
            reject(b, rejections, planned);
            return;
        }

        // The total up front, so a batch the balance cannot cover is refused before anything is held.
        long required = 0;
        for (Planned p : planned) required += p.prepared().priced().settleCents();
        long available = ledger.wallet(ctx.walletId()).getAvailableCents();
        if (required > available) {
            throw new CommercialException(ErrorCode.INSUFFICIENT_BALANCE,
                    "The available balance does not cover this batch")
                    .with("required", Money.format(required)).with("available", Money.format(available));
        }

        // Pass 2: hold and record, one by one.
        for (Planned p : planned) {
            Map<String, String> relations = new LinkedHashMap<>();
            relations.put("batchId", b.getBatchId());
            relations.put("batchIndex", BatchRules.indexKey(p.index()));
            relations.put(DISPATCH, INTAKE);
            try {
                queued.add(payouts.queue(ctx, p.request(), p.prepared(), relations));
            } catch (CommercialException e) {
                PayoutBatch.Rejection r = new PayoutBatch.Rejection(p.request().merchantReference().trim(), e.code().name(), e.getMessage());
                if (allOrNothing) {
                    rollback(queued, "The batch was rejected");
                    queued.clear();
                    rejections.add(r);
                    reject(b, rejections, planned);
                    return;
                }
                rejections.add(r);
            }
        }

        long amount = 0;
        long fee = 0;
        for (CommercialTransaction tx : queued) {
            amount += tx.getAmountCents();
            fee += tx.getFeeCents();
        }
        b.setAcceptedCount(queued.size());
        b.setTotalAmountCents(amount);
        b.setTotalFeeCents(fee);
        b.setRejected(rejections);
        b.setUpdatedAt(Instant.now());
        if (queued.isEmpty()) {
            b.setStatus(PayoutBatch.Status.REJECTED);
            b.setUniqueKey(null);
            b.setEventEmitted(true);
            b.setCompletedAt(Instant.now());
            batches.save(b);
            return;
        }
        batches.save(b);
        // Only now may the dispatcher see the items.
        mongo.updateMulti(Query.query(Criteria.where("relations.batchId").is(b.getBatchId()).and("relations." + DISPATCH).is(INTAKE)),
                new BasicUpdate(new Document("$set", new Document("relations." + DISPATCH, QUEUED))), CommercialTransaction.class);
    }

    /** An ALL_OR_NOTHING batch that was refused: every item is reported, the failing ones with their own reason. */
    private void reject(PayoutBatch b, List<PayoutBatch.Rejection> failures, List<Planned> planned) {
        List<PayoutBatch.Rejection> all = new ArrayList<>(failures);
        Set<String> already = new HashSet<>();
        failures.forEach(f -> already.add(f.getMerchantReference()));
        for (Planned p : planned) {
            String ref = p.request().merchantReference().trim();
            if (already.add(ref)) {
                all.add(new PayoutBatch.Rejection(ref, BATCH_REJECTED, "Not accepted because another item in the batch was rejected"));
            }
        }
        b.setStatus(PayoutBatch.Status.REJECTED);
        b.setUniqueKey(null);
        b.setRejected(all);
        b.setAcceptedCount(0);
        b.setTotalAmountCents(0);
        b.setTotalFeeCents(0);
        b.setEventEmitted(true);
        b.setUpdatedAt(Instant.now());
        b.setCompletedAt(Instant.now());
        batches.save(b);
    }

    /**
     * Cancels items that were recorded but never sent, releasing their money. The items are marked silent first
     * so no payout.failed event is raised for payouts the merchant was never told about.
     */
    void rollback(List<CommercialTransaction> items, String reason) {
        for (CommercialTransaction tx : items) {
            try {
                mongo.updateFirst(Query.query(Criteria.where("id").is(tx.getId())),
                        new BasicUpdate(new Document("$set", new Document("relations.silent", "true"))), CommercialTransaction.class);
                CommercialTransaction fresh = store.find(tx.getId()).orElse(tx);
                outcomes.apply(fresh, RailOutcome.of(TxStatus.CANCELLED, ResultCode.CUSTOMER_CANCELLED, reason));
            } catch (RuntimeException e) {
                log.error("Could not roll back payout {} of a rejected batch", tx.getId(), e);
            }
        }
    }

    /** 6.5 */
    public Map<String, Object> get(CommercialContext ctx, String batchId, boolean includeItems, String itemStatus,
                                   Integer page, Integer size) {
        PayoutBatch b = requireOwn(ctx, batchId);
        Map<String, Object> view = BatchViews.detail(b, counters(b.getBatchId()));
        if (includeItems) {
            int p = page == null ? 0 : page;
            int s = size == null ? 20 : size;
            if (p < 0) throw CommercialException.validation("page", "must be zero or more");
            if (s < 1 || s > 100) throw CommercialException.validation("size", "must be between 1 and 100");
            Criteria c = Criteria.where("type").is(TxType.PAYOUT.name()).and("relations.batchId").is(b.getBatchId());
            if (itemStatus != null && !itemStatus.isBlank()) {
                try {
                    c = c.and("status").is(TxStatus.valueOf(itemStatus.trim().toUpperCase(Locale.ROOT)).name());
                } catch (IllegalArgumentException e) {
                    throw CommercialException.validation("itemStatus", "is not a valid status");
                }
            }
            Pageable pageable = PageRequest.of(p, s, Sort.by(Sort.Direction.ASC, "relations.batchIndex"));
            Query q = Query.query(c);
            long total = mongo.count(q, CommercialTransaction.class);
            List<CommercialTransaction> content = mongo.find(q.with(pageable), CommercialTransaction.class);
            Page<CommercialTransaction> pg = new PageImpl<>(content, pageable, total);
            view.put("items", com.premisave.wallet.commercial.dto.PageViews.of(pg, BatchViews::item));
        }
        return view;
    }

    public BatchRules.Counters counters(String batchId) {
        return new BatchRules.Counters(
                count(batchId, TxStatus.PENDING, TxStatus.REQUIRES_ACTION),
                count(batchId, TxStatus.SUCCESS),
                count(batchId, TxStatus.FAILED, TxStatus.CANCELLED, TxStatus.REVERSED),
                count(batchId, TxStatus.TIMED_OUT));
    }

    private long count(String batchId, TxStatus... statuses) {
        Criteria c = Criteria.where("type").is(TxType.PAYOUT.name()).and("relations.batchId").is(batchId)
                .and("status").in(java.util.Arrays.stream(statuses).map(Enum::name).toList());
        return mongo.count(Query.query(c), CommercialTransaction.class);
    }

    List<CommercialTransaction> itemsOf(String batchId) {
        Query q = Query.query(Criteria.where("type").is(TxType.PAYOUT.name()).and("relations.batchId").is(batchId))
                .with(PageRequest.of(0, 500, Sort.by(Sort.Direction.ASC, "relations.batchIndex")));
        return mongo.find(q, CommercialTransaction.class);
    }

    private PayoutBatch requireOwn(CommercialContext ctx, String id) {
        return batches.findByBatchIdAndMerchantIdAndLivemode(id, ctx.merchantId(), ctx.livemode())
                .orElseThrow(() -> CommercialException.notFound("Batch"));
    }
}
