package com.premisave.wallet.commercial.service.billing;

import com.premisave.wallet.commercial.config.properties.CommercialBillingProperties;
import com.premisave.wallet.commercial.dto.billing.BillingViews;
import com.premisave.wallet.commercial.dto.sandbox.AdvanceClockRequest;
import com.premisave.wallet.commercial.entity.billing.Invoice;
import com.premisave.wallet.commercial.entity.billing.Subscription;
import com.premisave.wallet.commercial.enums.Environment;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.repository.billing.BillingSubscriptionRepository;
import com.premisave.wallet.commercial.security.CommercialContext;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.BasicUpdate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Sandbox helper 13.4: moves one subscription's clock forward and runs everything that would have
 * happened on the way, so billing, retries and events can be tried without waiting. The sandbox rail
 * settles payments a few seconds after they are made, so the helper waits briefly for each payment; when
 * a payment has not settled in time it stops and says so, and calling it again carries on.
 */
@Service("commercialSandboxBillingService")
public class SandboxBillingService {

    private static final Duration MAX_AHEAD = Duration.ofDays(366);
    private static final int MAX_STEPS = 24;
    private static final long POLL_MS = 200;

    private enum Kind { BILL, RETRY, RESUME }

    private record Step(Kind kind, Instant at, String invoiceId) {}

    private final SubscriptionService subscriptions;
    private final BillingEngine engine;
    private final BillingSubscriptionRepository subs;
    private final CommercialBillingProperties props;
    private final MongoTemplate mongo;

    public SandboxBillingService(SubscriptionService subscriptions, BillingEngine engine, BillingSubscriptionRepository subs,
                                 CommercialBillingProperties props, MongoTemplate mongo) {
        this.subscriptions = subscriptions;
        this.engine = engine;
        this.subs = subs;
        this.props = props;
        this.mongo = mongo;
    }

    public Map<String, Object> advanceClock(CommercialContext ctx, String id, AdvanceClockRequest req) {
        if (ctx.environment() != Environment.SANDBOX) throw CommercialException.notFound("Resource");
        Subscription s = subscriptions.require(ctx, id);
        Instant realNow = Instant.now();
        Instant clock = s.getClockAt() != null && s.getClockAt().isAfter(realNow) ? s.getClockAt() : realNow;
        Instant to = BillingInput.instant("to", req.to());
        if (to == null) throw CommercialException.validation("to", "is required");
        if (!to.isAfter(clock)) throw CommercialException.validation("to", "must be later than the subscription's clock, which is at " + clock);
        if (to.isAfter(realNow.plus(MAX_AHEAD))) throw CommercialException.validation("to", "may be at most one year from now");

        Instant reached = clock;
        String note = null;
        int steps = 0;
        while (true) {
            Subscription current = subs.findById(id).orElseThrow(() -> CommercialException.notFound("Subscription"));
            if (inFlight(id)) {
                note = "A payment is still in progress; call advance-clock again once it has settled";
                break;
            }
            Step step = nextStep(current, to);
            if (step == null) {
                reached = to;
                break;
            }
            if (steps++ >= MAX_STEPS) {
                note = "Stopped after " + MAX_STEPS + " steps; call advance-clock again to carry on";
                break;
            }
            Instant at = step.at().isBefore(reached) ? reached : step.at();
            switch (step.kind()) {
                case BILL -> engine.billDue(id, at);
                case RETRY -> engine.attemptScheduled(step.invoiceId(), at);
                case RESUME -> subscriptions.resumeScheduled(id, at);
            }
            reached = at;
            awaitSettled(id);
        }

        mongo.updateFirst(Query.query(Criteria.where("id").is(id)),
                new BasicUpdate(new Document("$set", new Document("clockAt", Date.from(reached)))), Subscription.class);
        Map<String, Object> view = BillingViews.subscription(subs.findById(id).orElse(s));
        view.put("clockAt", reached.toString());
        if (note != null) view.put("note", note);
        return view;
    }

    private Step nextStep(Subscription s, Instant to) {
        Step best = null;
        if (Subscription.BILLABLE.contains(s.getState()) && s.getNextBillingAt() != null && !s.getNextBillingAt().isAfter(to)) {
            best = new Step(Kind.BILL, s.getNextBillingAt(), null);
        }
        if (s.getState() == Subscription.State.PAUSED && s.getResumeAt() != null && !s.getResumeAt().isAfter(to)) {
            best = earlier(best, new Step(Kind.RESUME, s.getResumeAt(), null));
        }
        if (Subscription.RETRYABLE.contains(s.getState())) {
            Invoice retry = mongo.findOne(Query.query(Criteria.where("subscriptionId").is(s.getId())
                            .and("status").is(Invoice.Status.OPEN.name())
                            .and("nextRetryAt").lte(to))
                    .with(Sort.by(Sort.Direction.ASC, "nextRetryAt")), Invoice.class);
            if (retry != null) best = earlier(best, new Step(Kind.RETRY, retry.getNextRetryAt(), retry.getId()));
        }
        return best;
    }

    private static Step earlier(Step a, Step b) {
        return a == null || b.at().isBefore(a.at()) ? b : a;
    }

    /** True while an attempt of this subscription's invoices has not settled. */
    private boolean inFlight(String subscriptionId) {
        return mongo.count(Query.query(Criteria.where("subscriptionId").is(subscriptionId)
                .and("status").in(List.of(Invoice.Status.OPEN.name(), Invoice.Status.AWAITING_CUSTOMER.name()))
                .and("nextRetryAt").is(null)), Invoice.class) > 0;
    }

    private void awaitSettled(String subscriptionId) {
        long deadline = System.currentTimeMillis() + props.clockWaitMs();
        while (inFlight(subscriptionId) && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
