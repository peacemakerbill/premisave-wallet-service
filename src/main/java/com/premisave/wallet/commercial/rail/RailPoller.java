package com.premisave.wallet.commercial.rail;

import com.premisave.wallet.commercial.config.properties.CommercialRailsProperties;
import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.enums.TxType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds out what happened to open transactions on rails that cannot be told by a callback. For each rail that
 * says it is pollable, transactions still open after a short wait are looked up with the provider at growing
 * intervals, and any final answer goes to the RailResultSink. It is the safety net behind webhooks (a lost or
 * late webhook is picked up here) and the only path for providers whose callback address is shared with the
 * wallet service. Applying an answer twice is harmless, so overlapping with a webhook is fine.
 */
@Component("commercialRailPoller")
@ConditionalOnProperty(prefix = "commercial.rails.polling", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RailPoller {

    private static final Logger log = LoggerFactory.getLogger(RailPoller.class);
    private static final Duration FIRST_BACKOFF = Duration.ofSeconds(15);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);
    private static final int MAX_TRACKED = 5000;

    private record Poll(Instant next, Duration backoff) {}

    private final MongoTemplate mongo;
    private final List<PaymentRail> rails;
    private final RailResultSink sink;
    private final CommercialRailsProperties props;
    private final Map<String, Poll> polls = new ConcurrentHashMap<>();

    public RailPoller(MongoTemplate mongo, List<PaymentRail> rails, RailResultSink sink, CommercialRailsProperties props) {
        this.mongo = mongo;
        this.rails = rails;
        this.sink = sink;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${commercial.rails.polling.interval-ms:20000}")
    public void run() {
        for (PaymentRail rail : rails) {
            if (!rail.pollable()) continue;
            try {
                pollRail(rail, Instant.now());
            } catch (RuntimeException e) {
                log.error("Polling the {} rail failed", rail.id(), e);
            }
        }
        if (polls.size() > MAX_TRACKED) polls.clear();
    }

    int pollRail(PaymentRail rail, Instant now) {
        CommercialRailsProperties.Polling cfg = props.polling();
        Criteria c = Criteria.where("rail").is(rail.id().name())
                .and("type").in(TxType.PAYMENT.name(), TxType.PAYOUT.name(), TxType.REFUND.name())
                .and("status").in(TxStatus.OPEN.stream().map(Enum::name).toList())
                .and("createdAt").lt(now.minusSeconds(cfg.afterSeconds()));
        Query q = Query.query(c).with(PageRequest.of(0, cfg.batchSize(), Sort.by(Sort.Direction.ASC, "createdAt")));
        int settled = 0;
        for (CommercialTransaction tx : mongo.find(q, CommercialTransaction.class)) {
            Poll poll = polls.get(tx.getId());
            if (poll != null && poll.next().isAfter(now)) continue;
            try {
                RailOutcome outcome = rail.poll(tx);
                if (outcome != null) {
                    log.info("{} lookup settled {} {} as {}", rail.id(), tx.getType(), tx.getId(), outcome.status());
                    sink.onOutcome(tx.getId(), outcome);
                    polls.remove(tx.getId());
                    settled++;
                } else {
                    Duration next = poll == null ? FIRST_BACKOFF : poll.backoff().multipliedBy(2);
                    if (next.compareTo(MAX_BACKOFF) > 0) next = MAX_BACKOFF;
                    polls.put(tx.getId(), new Poll(now.plus(next), next));
                }
            } catch (RuntimeException e) {
                log.warn("{} lookup for {} failed: {}", rail.id(), tx.getId(), e.getMessage());
            }
        }
        return settled;
    }
}
