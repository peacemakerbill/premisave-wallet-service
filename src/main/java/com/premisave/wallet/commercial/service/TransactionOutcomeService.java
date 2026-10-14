package com.premisave.wallet.commercial.service;

import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.entity.MerchantHold;
import com.premisave.wallet.commercial.entity.MerchantWallet;
import com.premisave.wallet.commercial.entity.TxResult;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.enums.ResultCode;
import com.premisave.wallet.commercial.enums.TxType;
import com.premisave.wallet.commercial.rail.RailOutcome;
import com.premisave.wallet.commercial.rail.RailResponse;
import com.premisave.wallet.commercial.rail.RailResultSink;
import com.premisave.wallet.commercial.repository.MerchantWalletRepository;
import com.premisave.wallet.commercial.util.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Applies the final answer about a transaction. The status change is a
 * guarded transition, so when two reports race only one wins. The winner
 * then applies the money side (credit, capture, release or reversal), each
 * step of which is itself exactly-once, then raises the event. A sweeper
 * repeats the money and event steps for any transaction whose process stopped
 * half way, so a crash cannot leave money unapplied.
 */
@Service("commercialTransactionOutcomeService")
public class TransactionOutcomeService implements RailResultSink {

    private static final Logger log = LoggerFactory.getLogger(TransactionOutcomeService.class);
    private static final Set<TxStatus> FINALS = EnumSet.of(
            TxStatus.SUCCESS, TxStatus.FAILED, TxStatus.TIMED_OUT, TxStatus.CANCELLED, TxStatus.REVERSED);

    private final CommercialTransactionStore store;
    private final LedgerService ledger;
    private final MerchantWalletRepository wallets;
    private final EventService events;
    private final FeeRevenuePoster feeRevenue;
    private final MongoTemplate mongo;

    public TransactionOutcomeService(CommercialTransactionStore store, LedgerService ledger,
                                     MerchantWalletRepository wallets, EventService events,
                                     FeeRevenuePoster feeRevenue, MongoTemplate mongo) {
        this.store = store;
        this.ledger = ledger;
        this.wallets = wallets;
        this.events = events;
        this.feeRevenue = feeRevenue;
        this.mongo = mongo;
    }

    @Override
    public void onOutcome(String transactionId, RailOutcome outcome) {
        Optional<CommercialTransaction> current = store.find(transactionId);
        if (current.isEmpty()) {
            log.warn("Outcome received for unknown transaction {}", transactionId);
            return;
        }
        apply(current.get(), outcome);
    }

    /** Applies an outcome and returns the transaction as it stands afterwards. */
    public CommercialTransaction apply(CommercialTransaction tx, RailOutcome outcome) {
        TxStatus to = outcome.status();
        Set<TxStatus> from = to == TxStatus.REVERSED && tx.getType().usesHold()
                ? EnumSet.of(TxStatus.SUCCESS) : TxStatus.OPEN;

        if (!to.isFinal()) {
            // A progress report (for example PENDING after REQUIRES_ACTION) changes no money.
            Optional<CommercialTransaction> moved = store.transition(tx.getId(), from, to, null,
                    outcome.description(), providerReferenceUpdate(outcome));
            moved.ifPresent(events::emit);
            return moved.orElseGet(() -> store.find(tx.getId()).orElse(tx));
        }

        TxResult result = new TxResult(outcome.code(), outcome.description(), outcome.providerCode(),
                outcome.providerDescription());
        Optional<CommercialTransaction> won = store.transition(tx.getId(), from, to, result,
                outcome.description(), providerReferenceUpdate(outcome));
        if (won.isEmpty()) {
            return store.find(tx.getId()).orElse(tx);
        }
        CommercialTransaction done = won.get();
        finish(done);
        return store.find(done.getId()).orElse(done);
    }

    /** Records what a rail said when it accepted a request, finishing the transaction if the rail already has an answer. */
    public CommercialTransaction applyRailResponse(CommercialTransaction tx, RailResponse resp) {
        if (resp.status().isFinal()) {
            return apply(tx, new RailOutcome(resp.status(), resp.code(), resp.description(), null, null,
                    resp.providerReference()));
        }
        java.util.Map<String, Object> extra = new java.util.LinkedHashMap<>();
        if (resp.providerReference() != null) extra.put("providerReference", resp.providerReference());
        if (resp.nextAction() != null) extra.put("nextAction", resp.nextAction());
        Optional<CommercialTransaction> moved = store.transition(tx.getId(), TxStatus.OPEN, resp.status(), null,
                "Accepted by " + tx.getProvider(), extra);
        moved.ifPresent(events::emit);
        return moved.orElseGet(() -> store.find(tx.getId()).orElse(tx));
    }

    /** Collections the customer never completed are closed once their expiry has passed. */
    @Scheduled(fixedDelayString = "${commercial.expiry-interval-ms:60000}")
    public void expireStalePayments() {
        Criteria c = Criteria.where("type").is(TxType.PAYMENT.name())
                .and("status").in(TxStatus.OPEN.stream().map(Enum::name).toList())
                .and("expiresAt").lt(Instant.now());
        for (CommercialTransaction tx : mongo.find(Query.query(c).with(PageRequest.of(0, 100)), CommercialTransaction.class)) {
            try {
                apply(tx, RailOutcome.of(TxStatus.TIMED_OUT, ResultCode.CUSTOMER_TIMEOUT, "The payment expired before the customer completed it"));
            } catch (RuntimeException e) {
                log.error("Could not expire payment {}", tx.getId(), e);
            }
        }
    }

    /** Money step then event step, both safe to repeat. */
    void finish(CommercialTransaction tx) {
        applyMoney(tx);
        store.markLedgerApplied(tx.getId());
        feeRevenue.post(tx);
        events.emit(tx);
    }

    private void applyMoney(CommercialTransaction tx) {
        MerchantWallet wallet = wallets.findByMerchantIdAndLivemode(tx.getMerchantId(), tx.isLivemode())
                .orElseThrow(() -> new IllegalStateException("Wallet missing for merchant " + tx.getMerchantId()));
        String ref = tx.getMerchantReference();

        if (tx.getType() == TxType.PAYMENT) {
            if (tx.getStatus() == TxStatus.SUCCESS) {
                ledger.credit(wallet.getWalletId(), tx.getId(), tx.getSettleCents(), tx.getFeeCents(),
                        "Payment " + ref + " (gross " + Money.format(tx.getAmountCents())
                                + ", fee " + Money.format(tx.getFeeCents()) + ")");
            }
            return;
        }

        if (tx.getType().usesHold()) {
            String what = tx.getType().label();
            switch (tx.getStatus()) {
                case SUCCESS -> {
                    ledger.capture(wallet.getWalletId(), tx.getId(), tx.getSettleCents(), tx.getFeeCents(),
                            what + " " + ref + " (amount " + Money.format(tx.getAmountCents())
                                    + ", fee " + Money.format(tx.getFeeCents()) + ")");
                    closeHold(tx, MerchantHold.CAPTURED);
                }
                case FAILED, TIMED_OUT, CANCELLED -> {
                    ledger.release(wallet.getWalletId(), tx.getId(), tx.getSettleCents(), what + " " + ref + " released");
                    closeHold(tx, MerchantHold.RELEASED);
                }
                case REVERSED -> ledger.reverse(wallet.getWalletId(), tx.getId(), tx.getAmountCents(),
                        what + " " + ref + " reversed by the provider");
                default -> { }
            }
        }
    }

    private void closeHold(CommercialTransaction tx, String state) {
        if (tx.getHoldId() == null) return;
        mongo.updateFirst(Query.query(Criteria.where("holdId").is(tx.getHoldId()).and("state").is(MerchantHold.ACTIVE)),
                new org.springframework.data.mongodb.core.query.BasicUpdate(new org.bson.Document("$set",
                        new org.bson.Document("state", state).append("closedAt", java.util.Date.from(Instant.now())))),
                MerchantHold.class);
    }

    private static java.util.Map<String, Object> providerReferenceUpdate(RailOutcome outcome) {
        return outcome.providerReference() == null ? null : java.util.Map.of("providerReference", outcome.providerReference());
    }

    /** Re-runs the money and event steps for transactions that reached a final status but were not finished. */
    @Scheduled(fixedDelayString = "${commercial.recovery-interval-ms:30000}")
    public void recoverUnfinished() {
        Criteria c = Criteria.where("ledgerApplied").is(false)
                .and("status").in(FINALS.stream().map(Enum::name).toList())
                .and("updatedAt").lt(Instant.now().minusSeconds(30));
        List<CommercialTransaction> stuck = mongo.find(Query.query(c).with(PageRequest.of(0, 50)), CommercialTransaction.class);
        for (CommercialTransaction tx : stuck) {
            try {
                log.warn("Finishing transaction {} that stopped after reaching {}", tx.getId(), tx.getStatus());
                finish(tx);
            } catch (RuntimeException e) {
                log.error("Could not finish transaction {}", tx.getId(), e);
            }
        }
    }
}
