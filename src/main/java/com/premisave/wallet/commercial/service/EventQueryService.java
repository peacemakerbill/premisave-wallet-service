package com.premisave.wallet.commercial.service;

import com.premisave.wallet.commercial.dto.ResendRequest;
import com.premisave.wallet.commercial.dto.TestEventRequest;
import com.premisave.wallet.commercial.entity.CallbackRegistration;
import com.premisave.wallet.commercial.entity.CommercialEvent;
import com.premisave.wallet.commercial.entity.EventTarget;
import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.enums.ForwardStatus;
import com.premisave.wallet.commercial.enums.RegistrationStatus;
import com.premisave.wallet.commercial.enums.RegistrationType;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.repository.CallbackRegistrationRepository;
import com.premisave.wallet.commercial.repository.CommercialEventRepository;
import com.premisave.wallet.commercial.security.CommercialContext;
import com.premisave.wallet.commercial.util.CallbackUrlValidator;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Reading, re-sending and sampling events for the calling merchant and environment. */
@Service
public class EventQueryService {

    private final CommercialEventRepository events;
    private final CallbackRegistrationRepository registrations;
    private final EventService eventService;
    private final MongoTemplate mongo;

    public EventQueryService(CommercialEventRepository events, CallbackRegistrationRepository registrations,
                             EventService eventService, MongoTemplate mongo) {
        this.events = events;
        this.registrations = registrations;
        this.eventService = eventService;
        this.mongo = mongo;
    }

    public Page<CommercialEvent> list(CommercialContext ctx, Map<String, String> f, Pageable pageable) {
        List<Criteria> all = new ArrayList<>();
        all.add(Criteria.where("merchantId").is(ctx.merchantId()));
        all.add(Criteria.where("livemode").is(ctx.livemode()));

        String type = f.get("type");
        if (type != null && !type.isBlank()) {
            if (type.equals("*")) {
                // every type
            } else if (type.endsWith(".*")) {
                all.add(Criteria.where("type").regex("^" + Pattern.quote(type.substring(0, type.length() - 1))));
            } else {
                all.add(Criteria.where("type").is(type));
            }
        }
        String objectId = f.get("objectId");
        if (objectId != null && !objectId.isBlank()) all.add(Criteria.where("objectId").is(objectId));
        String forward = f.get("forwardStatus");
        if (forward != null && !forward.isBlank()) {
            try {
                all.add(Criteria.where("forwardStatus").is(ForwardStatus.valueOf(forward.trim().toUpperCase(Locale.ROOT)).name()));
            } catch (IllegalArgumentException e) {
                throw CommercialException.validation("forwardStatus", "is not a valid value");
            }
        }
        Instant from = instant(f.get("from"), "from");
        Instant to = instant(f.get("to"), "to");
        if (from != null) all.add(Criteria.where("createdAt").gte(from));
        if (to != null) all.add(Criteria.where("createdAt").lte(to));

        Query q = Query.query(new Criteria().andOperator(all.toArray(new Criteria[0])));
        long total = mongo.count(q, CommercialEvent.class);
        return new PageImpl<>(mongo.find(q.with(pageable), CommercialEvent.class), pageable, total);
    }

    public CommercialEvent get(CommercialContext ctx, String eventId) {
        return events.findByEventIdAndMerchantIdAndLivemode(eventId, ctx.merchantId(), ctx.livemode())
                .orElseThrow(() -> CommercialException.notFound("Event"));
    }

    /** Puts an event back in the outbox, optionally aimed at one registration or one URL. Same eventId, same payload. */
    public Map<String, Object> resend(CommercialContext ctx, String eventId, ResendRequest req) {
        CommercialEvent e = get(ctx, eventId);
        List<EventTarget> replacement = null;
        if (req != null && req.registrationId() != null && !req.registrationId().isBlank()) {
            CallbackRegistration r = registrations.findByIdAndMerchantIdAndLivemode(req.registrationId(), ctx.merchantId(), ctx.livemode())
                    .orElseThrow(() -> CommercialException.notFound("Registration"));
            if (r.getType() != RegistrationType.EVENT || r.getStatus() != RegistrationStatus.ACTIVE) {
                throw CommercialException.invalidState("The registration is not an active event registration");
            }
            replacement = List.of(new EventTarget(r.getUrl(), "REGISTERED", r.getId()));
        } else if (req != null && req.callbackUrl() != null && !req.callbackUrl().isBlank()) {
            String reason = CallbackUrlValidator.check(req.callbackUrl(), ctx.environment()).orElse(null);
            if (reason != null) throw new CommercialException(ErrorCode.INVALID_CALLBACK_URL, reason);
            replacement = List.of(new EventTarget(req.callbackUrl().trim(), "REQUEST", null));
        }
        if (replacement == null && e.getTargets().isEmpty()) {
            throw CommercialException.invalidState("The event has no delivery targets; give a registrationId or callbackUrl");
        }
        Instant now = Instant.now();
        eventService.requeue(e, replacement);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("eventId", e.getEventId());
        out.put("queuedAt", now.toString());
        return out;
    }

    /** Sandbox: sends a sample event to one registration, or to every active one that matches the type. */
    public Map<String, Object> sendTest(CommercialContext ctx, TestEventRequest req) {
        EventService.requireConcreteType(req.eventType());
        List<EventTarget> targets;
        String objectId = "sample";
        if (req.registrationId() != null && !req.registrationId().isBlank()) {
            CallbackRegistration r = registrations.findByIdAndMerchantIdAndLivemode(req.registrationId(), ctx.merchantId(), ctx.livemode())
                    .orElseThrow(() -> CommercialException.notFound("Registration"));
            if (r.getType() != RegistrationType.EVENT || r.getStatus() != RegistrationStatus.ACTIVE) {
                throw CommercialException.invalidState("The registration is not an active event registration");
            }
            targets = List.of(new EventTarget(r.getUrl(), "REGISTERED", r.getId()));
            objectId = r.getId();
        } else {
            targets = eventService.targetsFor(ctx.appId(), ctx.livemode(), req.eventType(), null, false);
            if (targets.isEmpty()) {
                throw CommercialException.invalidState("No active registration matches " + req.eventType());
            }
        }
        CommercialEvent e = eventService.emitDirect(req.eventType(), ctx.merchantId(), ctx.appId(), ctx.livemode(),
                objectId, CallbackRegistrationService.sampleData(req.eventType(), objectId), targets);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("eventId", e.getEventId());
        return out;
    }

    private static Instant instant(String value, String field) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw CommercialException.validation(field, "must be an ISO 8601 UTC timestamp such as 2026-10-14T00:00:00Z");
        }
    }
}
