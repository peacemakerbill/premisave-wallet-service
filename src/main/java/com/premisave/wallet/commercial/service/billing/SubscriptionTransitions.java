package com.premisave.wallet.commercial.service.billing;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.BasicUpdate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import com.premisave.wallet.commercial.entity.billing.Subscription;
import com.premisave.wallet.commercial.repository.billing.BillingSubscriptionRepository;
import com.premisave.wallet.commercial.service.EventTypes;

/**
 * The state changes of a subscription that more than one part of the system can cause: a payment
 * failing, a payment recovering, the subscription ending. Each is a single guarded update on the state
 * it expects, so repeating one (a retried listener, two instances) changes nothing the second time.
 */
@Component("commercialSubscriptionTransitions")
public class SubscriptionTransitions {

    private static final List<String> LIVE = Subscription.LIVE.stream().map(Enum::name).toList();

    private final BillingSubscriptionRepository subs;
    private final InvoiceStore invoices;
    private final BillingEvents events;
    private final MongoTemplate mongo;

    public SubscriptionTransitions(BillingSubscriptionRepository subs, InvoiceStore invoices, BillingEvents events,
                                   MongoTemplate mongo) {
        this.subs = subs;
        this.invoices = invoices;
        this.events = events;
        this.mongo = mongo;
    }

    /** A payment failed (and counted): ACTIVE or TRIALING becomes PAST_DUE. */
    public Optional<Subscription> markPastDue(String id) {
        Optional<Subscription> after = apply(id, Criteria.where("state").in(
                        List.of(Subscription.State.ACTIVE.name(), Subscription.State.TRIALING.name())),
                new Document("state", Subscription.State.PAST_DUE.name()), null);
        after.ifPresent(s -> events.subscription(EventTypes.SUBSCRIPTION_PAST_DUE, s));
        return after;
    }

    /** The outstanding invoice was paid: PAST_DUE becomes ACTIVE again. */
    public Optional<Subscription> markRecovered(String id) {
        Optional<Subscription> after = apply(id, Criteria.where("state").is(Subscription.State.PAST_DUE.name()),
                new Document("state", Subscription.State.ACTIVE.name()), null);
        after.ifPresent(s -> events.subscription(EventTypes.SUBSCRIPTION_ACTIVATED, s));
        return after;
    }

    /**
     * Ends a subscription (CANCELLED or EXPIRED) and voids the invoices still waiting. The slot for the
     * customer and plan is freed so a new subscription can be created.
     */
    public Optional<Subscription> terminate(String id, Subscription.State target, String reason, Instant now) {
        Document set = new Document("state", target.name())
                .append("cancelledAt", Date.from(now))
                .append("cancelReason", reason)
                .append("cancelAtPeriodEnd", false)
                .append("upcomingNotified", true);
        Document unset = new Document("activeKey", "").append("resumeAt", "").append("pauseReason", "");
        Optional<Subscription> after = apply(id, Criteria.where("state").in(LIVE), set, unset);
        after.ifPresent(s -> {
            invoices.voidOpenFor(s.getId());
            events.subscription(EventTypes.SUBSCRIPTION_CANCELLED, s);
        });
        return after;
    }

    /**
     * One guarded update: only when the subscription is in the state the caller expects, and it counts as a
     * new version. Returns the subscription as stored afterwards, or empty when the guard did not match.
     */
    public Optional<Subscription> apply(String id, Criteria expected, Document set, Document unset) {
        Document s = new Document(set).append("updatedAt", Date.from(Instant.now()));
        Document update = new Document("$set", s).append("$inc", new Document("version", 1L));
        if (unset != null && !unset.isEmpty()) update.append("$unset", unset);
        long changed = mongo.updateFirst(Query.query(new Criteria().andOperator(Criteria.where("id").is(id), expected)),
                new BasicUpdate(update), Subscription.class).getModifiedCount();
        return changed == 1 ? subs.findById(id) : Optional.empty();
    }
}
