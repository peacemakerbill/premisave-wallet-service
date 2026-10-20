package com.premisave.wallet.commercial.rail.mpesa;

import com.premisave.wallet.commercial.config.properties.CommercialMpesaProperties;
import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.enums.RailId;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.enums.TxType;
import com.premisave.wallet.commercial.rail.RailResultSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Safety net for M-Pesa. A payment whose callback never arrived is looked up
 * with Safaricom's STK status query, so a customer who really paid is not left
 * as an expired payment. Each payment is asked about at growing intervals so
 * Safaricom's rate limits are respected.
 *
 * Payouts cannot be looked up this way; one that has waited unusually long for
 * its result is logged as an error so an operator can check it by hand.
 */
@Component("commercialMpesaStkReconciler")
@ConditionalOnProperty(prefix = "commercial.mpesa", name = "enabled", havingValue = "true")
public class MpesaStkReconciler {

    private static final Logger log = LoggerFactory.getLogger(MpesaStkReconciler.class);
    private static final Duration FIRST_BACKOFF = Duration.ofSeconds(30);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);
    private static final Duration STUCK_PAYOUT_AFTER = Duration.ofMinutes(30);

    private record Poll(Instant next, Duration backoff) {}

    private final MongoTemplate mongo;
    private final MpesaDarajaClient client;
    private final RailResultSink sink;
    private final CommercialMpesaProperties props;
    private final Map<String, Poll> polls = new ConcurrentHashMap<>();

    public MpesaStkReconciler(MongoTemplate mongo, MpesaDarajaClient client, RailResultSink sink,
                              CommercialMpesaProperties props) {
        this.mongo = mongo;
        this.client = client;
        this.sink = sink;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${commercial.mpesa.poll-interval-ms:30000}")
    public void run() {
        try {
            pollPayments();
            reportStuckPayouts();
        } catch (RuntimeException e) {
            log.error("M-Pesa reconciliation run failed", e);
        }
    }

    void pollPayments() {
        Instant now = Instant.now();
        Criteria c = Criteria.where("type").is(TxType.PAYMENT.name())
                .and("rail").is(RailId.MPESA.name())
                .and("status").in(TxStatus.OPEN.stream().map(Enum::name).toList())
                .and("createdAt").lt(now.minusSeconds(props.pollAfterSeconds()))
                .and("providerReference").ne(null);
        for (CommercialTransaction tx : mongo.find(Query.query(c).with(PageRequest.of(0, 50)), CommercialTransaction.class)) {
            Poll poll = polls.get(tx.getId());
            if (poll != null && poll.next().isAfter(now)) continue;
            try {
                MpesaDarajaClient.StkStatus status = client.stkQuery(tx.getProviderReference());
                if (status.answered()) {
                    log.info("M-Pesa status query settled payment {} with code {}", tx.getId(), status.resultCode());
                    sink.onOutcome(tx.getId(), MpesaResultMapper.stk(status.resultCode(), status.resultDesc(), null));
                    polls.remove(tx.getId());
                } else {
                    Duration next = poll == null ? FIRST_BACKOFF : poll.backoff().multipliedBy(2);
                    if (next.compareTo(MAX_BACKOFF) > 0) next = MAX_BACKOFF;
                    polls.put(tx.getId(), new Poll(now.plus(next), next));
                }
            } catch (RuntimeException e) {
                log.warn("M-Pesa status query for {} failed: {}", tx.getId(), e.getMessage());
            }
        }
        polls.keySet().removeIf(id -> mongo.count(Query.query(Criteria.where("id").is(id)
                .and("status").in(TxStatus.OPEN.stream().map(Enum::name).toList())), CommercialTransaction.class) == 0);
    }

    void reportStuckPayouts() {
        Criteria c = Criteria.where("type").in(TxType.PAYOUT.name(), TxType.REFUND.name())
                .and("rail").is(RailId.MPESA.name())
                .and("status").in(TxStatus.OPEN.stream().map(Enum::name).toList())
                .and("createdAt").lt(Instant.now().minus(STUCK_PAYOUT_AFTER));
        for (CommercialTransaction tx : mongo.find(Query.query(c).with(PageRequest.of(0, 20)), CommercialTransaction.class)) {
            log.error("M-Pesa payout {} has had no result for over {} minutes; check it in the M-Pesa portal", tx.getId(),
                    STUCK_PAYOUT_AFTER.toMinutes());
        }
    }
}
