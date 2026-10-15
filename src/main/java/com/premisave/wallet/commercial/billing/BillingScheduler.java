package com.premisave.wallet.commercial.billing;

import com.premisave.wallet.commercial.config.CommercialBillingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Drives subscription billing. Each run does, in order: bill the subscriptions whose period has ended,
 * retry invoices whose retry time has come, finish attempts that stalled, resume paused subscriptions
 * whose time has come, and send invoice.upcoming notices. Every step is safe to repeat and safe to run
 * on several instances at once, because the work itself is made of guarded updates.
 */
@Component("commercialBillingScheduler")
@ConditionalOnProperty(prefix = "commercial.billing", name = "scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class BillingScheduler {

    private static final Logger log = LoggerFactory.getLogger(BillingScheduler.class);

    private final BillingEngine engine;
    private final SubscriptionService subscriptions;
    private final CommercialBillingProperties props;
    private final MongoTemplate mongo;

    public BillingScheduler(BillingEngine engine, SubscriptionService subscriptions, CommercialBillingProperties props,
                            MongoTemplate mongo) {
        this.engine = engine;
        this.subscriptions = subscriptions;
        this.props = props;
        this.mongo = mongo;
    }

    @Scheduled(fixedDelayString = "${commercial.billing.scheduler-interval-ms:60000}")
    public void run() {
        Instant now = Instant.now();
        int limit = props.batchSize();
        try {
            billDue(now, limit);
            retryDue(now, limit);
            recoverStalled(now, limit);
            subscriptions.resumeDue(now, limit);
            notifyUpcoming(now, limit);
        } catch (RuntimeException e) {
            log.error("Billing run failed", e);
        }
    }

    void billDue(Instant now, int limit) {
        for (Subscription s : engine.dueSubscriptions(now, limit)) {
            try {
                engine.billDue(s.getId(), now);
            } catch (RuntimeException e) {
                log.error("Billing subscription {} failed", s.getId(), e);
            }
        }
    }

    void retryDue(Instant now, int limit) {
        Criteria c = Criteria.where("status").is(Invoice.Status.OPEN.name()).and("nextRetryAt").lte(now);
        for (Invoice inv : mongo.find(Query.query(c).with(Sort.by(Sort.Direction.ASC, "nextRetryAt")).limit(limit), Invoice.class)) {
            try {
                engine.attemptScheduled(inv.getId(), now);
            } catch (RuntimeException e) {
                log.error("Retrying invoice {} failed", inv.getId(), e);
            }
        }
    }

    /** Invoices with an attempt that has not moved for a while: nothing is waiting to retry them. */
    void recoverStalled(Instant now, int limit) {
        Instant cutoff = now.minus(Duration.ofMinutes(props.stuckMinutes()));
        Criteria c = Criteria.where("status").in(List.of(Invoice.Status.OPEN.name(), Invoice.Status.AWAITING_CUSTOMER.name()))
                .and("nextRetryAt").is(null)
                .and("updatedAt").lte(cutoff);
        for (Invoice inv : mongo.find(Query.query(c).limit(limit), Invoice.class)) {
            try {
                engine.reconcile(inv.getId(), now);
            } catch (RuntimeException e) {
                log.error("Recovering invoice {} failed", inv.getId(), e);
            }
        }
    }

    void notifyUpcoming(Instant now, int limit) {
        Instant horizon = now.plus(Duration.ofDays(props.upcomingDays()));
        Criteria c = Criteria.where("state").in(Subscription.BILLABLE.stream().map(Enum::name).toList())
                .and("nextBillingAt").gt(now).lte(horizon)
                .and("upcomingNotified").is(false)
                .and("cancelAtPeriodEnd").is(false);
        for (Subscription s : mongo.find(Query.query(c).limit(limit), Subscription.class)) {
            try {
                engine.notifyUpcoming(s);
            } catch (RuntimeException e) {
                log.error("Upcoming notice for subscription {} failed", s.getId(), e);
            }
        }
    }
}
