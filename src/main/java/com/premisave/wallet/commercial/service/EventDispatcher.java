package com.premisave.wallet.commercial.service;

import com.premisave.wallet.commercial.config.properties.CommercialProperties;
import com.premisave.wallet.commercial.entity.CommercialEvent;
import com.premisave.wallet.commercial.entity.EventTarget;
import com.premisave.wallet.commercial.enums.ForwardStatus;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.BasicUpdate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Forwards outbox events to the developer service with retries. Delivery is
 * at least once, so receivers deduplicate on eventId. Retries back off from
 * 30 seconds up to 6 hours, over about 20 hours across 12 attempts, after
 * which the event is marked FAILED and can be sent again through the resend
 * endpoint.
 */
@Component("commercialEventDispatcher")
public class EventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EventDispatcher.class);
    private static final long LOCK_SECONDS = 60;
    private static final long BASE_DELAY_SECONDS = 30;
    private static final long MAX_DELAY_SECONDS = 6 * 3600;

    private final MongoTemplate mongo;
    private final DeveloperServiceClient client;
    private final JsonMapper json;
    private final CommercialProperties.Events config;
    private boolean warnedUnconfigured;

    public EventDispatcher(MongoTemplate mongo, DeveloperServiceClient client, JsonMapper json,
                           CommercialProperties props) {
        this.mongo = mongo;
        this.client = client;
        this.json = json;
        this.config = props.events();
    }

    @Scheduled(fixedDelayString = "${commercial.events.dispatch-interval-ms:2000}")
    public void dispatchDue() {
        if (!config.dispatcherEnabled()) return;
        if (!client.configured()) {
            if (!warnedUnconfigured) {
                log.warn("commercial.events.developer-service-url is not set; events are kept in the outbox and not forwarded");
                warnedUnconfigured = true;
            }
            return;
        }
        for (int i = 0; i < config.dispatchBatchSize(); i++) {
            CommercialEvent event = claimNext();
            if (event == null) return;
            deliver(event);
        }
    }

    void deliver(CommercialEvent event) {
        String reason;
        try {
            reason = client.forward(event, serialise(event));
        } catch (RuntimeException e) {
            reason = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        Instant now = Instant.now();
        Document set = new Document("lockedUntil", null);
        if (reason == null) {
            set.append("forwardStatus", ForwardStatus.DELIVERED_TO_DEVELOPER_SERVICE.name())
                    .append("deliveredAt", Date.from(now)).append("lastError", null);
        } else {
            int attempts = event.getAttempts() + 1;
            if (attempts >= config.maxAttempts()) {
                set.append("forwardStatus", ForwardStatus.FAILED.name());
                log.warn("Event {} could not be forwarded after {} attempts: {}", event.getEventId(), attempts, reason);
            } else {
                set.append("nextAttemptAt", Date.from(now.plusSeconds(backoffSeconds(attempts))));
            }
            set.append("lastError", reason);
        }
        Document update = new Document("$set", set);
        if (reason != null) update.append("$inc", new Document("attempts", 1));
        mongo.findAndModify(Query.query(Criteria.where("eventId").is(event.getEventId())), new BasicUpdate(update),
                FindAndModifyOptions.options().returnNew(true), CommercialEvent.class);
    }

    /** 30s, 60s, 120s ... capped at 6h, with up to 20% jitter. attempts is the number already made (1 for the first retry). */
    static long backoffSeconds(int attempts) {
        long exp = BASE_DELAY_SECONDS << Math.min(attempts - 1, 20);
        long capped = Math.min(exp, MAX_DELAY_SECONDS);
        long jitter = (long) (capped * 0.2 * ThreadLocalRandom.current().nextDouble());
        return capped + jitter;
    }

    private CommercialEvent claimNext() {
        Instant now = Instant.now();
        Criteria due = new Criteria().andOperator(
                Criteria.where("forwardStatus").is(ForwardStatus.PENDING.name()),
                Criteria.where("nextAttemptAt").lte(now),
                new Criteria().orOperator(Criteria.where("lockedUntil").is(null), Criteria.where("lockedUntil").lt(now)));
        Query q = Query.query(due).with(Sort.by(Sort.Direction.ASC, "nextAttemptAt"));
        Document lock = new Document("$set", new Document("lockedUntil", Date.from(now.plusSeconds(LOCK_SECONDS))));
        return mongo.findAndModify(q, new BasicUpdate(lock), FindAndModifyOptions.options().returnNew(true),
                CommercialEvent.class);
    }

    /** The body the developer service receives: the event plus the targets it must deliver to. */
    String serialise(CommercialEvent event) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", event.getEventId());
        body.put("type", event.getType());
        body.put("createdAt", event.getCreatedAt().toString());
        body.put("livemode", event.isLivemode());
        body.put("merchantId", event.getMerchantId());
        body.put("appId", event.getAppId());
        List<Map<String, Object>> targets = new ArrayList<>();
        for (EventTarget t : event.getTargets()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("url", t.getUrl());
            m.put("source", t.getSource());
            m.put("registrationId", t.getRegistrationId());
            targets.add(m);
        }
        body.put("targets", targets);
        body.put("data", event.getData());
        return json.writeValueAsString(body);
    }
}
