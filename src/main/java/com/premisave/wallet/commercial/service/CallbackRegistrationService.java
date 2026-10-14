package com.premisave.wallet.commercial.service;

import com.premisave.wallet.commercial.config.CommercialProperties;
import com.premisave.wallet.commercial.dto.RegistrationRequest;
import com.premisave.wallet.commercial.dto.RegistrationStatusRequest;
import com.premisave.wallet.commercial.dto.TestEventRequest;
import com.premisave.wallet.commercial.entity.CallbackRegistration;
import com.premisave.wallet.commercial.entity.CommercialEvent;
import com.premisave.wallet.commercial.entity.EventTarget;
import com.premisave.wallet.commercial.enums.Environment;
import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.enums.RegistrationStatus;
import com.premisave.wallet.commercial.enums.RegistrationType;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.repository.CallbackRegistrationRepository;
import com.premisave.wallet.commercial.security.CommercialContext;
import com.premisave.wallet.commercial.util.CallbackUrlValidator;
import com.premisave.wallet.commercial.util.EventTypeMatcher;
import com.premisave.wallet.commercial.util.Ids;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.BasicUpdate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Callback URLs a merchant app registers for events (and for synchronous validation). */
@Service
public class CallbackRegistrationService {

    private static final String VALIDATION_EVENT = "payment.validation";

    private final CommercialProperties props;
    private final CallbackRegistrationRepository registrations;
    private final DeveloperServiceClient developerService;
    private final EventService events;
    private final MongoTemplate mongo;

    public CallbackRegistrationService(CommercialProperties props, CallbackRegistrationRepository registrations,
                                       DeveloperServiceClient developerService, EventService events,
                                       MongoTemplate mongo) {
        this.props = props;
        this.registrations = registrations;
        this.developerService = developerService;
        this.events = events;
        this.mongo = mongo;
    }

    public CallbackRegistration create(CommercialContext ctx, RegistrationRequest req) {
        if (req.type() == null) throw CommercialException.validation("type", "is required (EVENT or VALIDATION)");
        String url = requireUrl(req.url(), ctx.environment());
        List<String> eventTypes = eventTypesFor(req.type(), req.eventTypes(), true);
        String onTimeout = onTimeoutFor(req.type(), req.onTimeout(), "REJECT");

        if (registrations.findByAppIdAndLivemodeAndUrl(ctx.appId(), ctx.livemode(), url).isPresent()) {
            throw new CommercialException(ErrorCode.DUPLICATE_REQUEST, "This URL is already registered for the app");
        }
        if (registrations.countByAppIdAndLivemode(ctx.appId(), ctx.livemode()) >= props.registrations().maxPerApp()) {
            throw new CommercialException(ErrorCode.CALLBACK_REGISTRATION_LIMIT,
                    "An app can have at most " + props.registrations().maxPerApp() + " registrations per environment");
        }

        Instant now = Instant.now();
        CallbackRegistration r = new CallbackRegistration();
        r.setId(Ids.next("cbr"));
        r.setMerchantId(ctx.merchantId());
        r.setAppId(ctx.appId());
        r.setLivemode(ctx.livemode());
        r.setType(req.type());
        r.setUrl(url);
        r.setEventTypes(eventTypes);
        r.setOnTimeout(onTimeout);
        r.setDescription(req.description());
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        if (ctx.environment() == Environment.LIVE) {
            r.setStatus(RegistrationStatus.PENDING_VERIFICATION);
        } else {
            r.setStatus(RegistrationStatus.ACTIVE);
            r.setVerifiedAt(now);
        }
        try {
            return registrations.insert(r);
        } catch (DuplicateKeyException raced) {
            throw new CommercialException(ErrorCode.DUPLICATE_REQUEST, "This URL is already registered for the app");
        }
    }

    public Page<CallbackRegistration> list(CommercialContext ctx, String status, String type, String appId, Pageable pageable) {
        List<Criteria> all = new ArrayList<>();
        all.add(Criteria.where("merchantId").is(ctx.merchantId()));
        all.add(Criteria.where("livemode").is(ctx.livemode()));
        if (status != null && !status.isBlank()) all.add(Criteria.where("status").is(parse(RegistrationStatus.class, status, "status").name()));
        if (type != null && !type.isBlank()) all.add(Criteria.where("type").is(parse(RegistrationType.class, type, "type").name()));
        if (appId != null && !appId.isBlank()) all.add(Criteria.where("appId").is(appId));
        Query q = Query.query(new Criteria().andOperator(all.toArray(new Criteria[0])));
        long total = mongo.count(q, CallbackRegistration.class);
        return new PageImpl<>(mongo.find(q.with(pageable), CallbackRegistration.class), pageable, total);
    }

    public CallbackRegistration get(CommercialContext ctx, String id) {
        return registrations.findByIdAndMerchantIdAndLivemode(id, ctx.merchantId(), ctx.livemode())
                .orElseThrow(() -> CommercialException.notFound("Registration"));
    }

    public CallbackRegistration update(CommercialContext ctx, String id, RegistrationRequest req) {
        CallbackRegistration r = get(ctx, id);
        boolean urlChanged = false;
        if (req.url() != null) {
            String url = requireUrl(req.url(), ctx.environment());
            if (!url.equals(r.getUrl())) {
                registrations.findByAppIdAndLivemodeAndUrl(r.getAppId(), r.isLivemode(), url).ifPresent(other -> {
                    throw new CommercialException(ErrorCode.DUPLICATE_REQUEST, "This URL is already registered for the app");
                });
                r.setUrl(url);
                urlChanged = true;
            }
        }
        if (req.eventTypes() != null) r.setEventTypes(eventTypesFor(r.getType(), req.eventTypes(), false));
        if (req.onTimeout() != null) r.setOnTimeout(onTimeoutFor(r.getType(), req.onTimeout(), r.getOnTimeout()));
        if (req.description() != null) r.setDescription(req.description());
        if (urlChanged && ctx.environment() == Environment.LIVE) {
            r.setStatus(RegistrationStatus.PENDING_VERIFICATION);
            r.setStatusReason(null);
            r.setVerifiedAt(null);
        }
        r.setUpdatedAt(Instant.now());
        return registrations.save(r);
    }

    public void delete(CommercialContext ctx, String id) {
        CallbackRegistration r = get(ctx, id);
        registrations.delete(r);
        // Events not yet forwarded stop pointing at the registration; one left with no target needs no delivery.
        mongo.updateMulti(Query.query(Criteria.where("forwardStatus").is("PENDING").and("targets.registrationId").is(id)),
                new BasicUpdate(new Document("$pull", new Document("targets", new Document("registrationId", id)))),
                CommercialEvent.class);
        mongo.updateMulti(Query.query(Criteria.where("forwardStatus").is("PENDING").and("targets").size(0)),
                new BasicUpdate(new Document("$set", new Document("forwardStatus", "NOT_REQUIRED"))),
                CommercialEvent.class);
    }

    /** Live: asks the developer service to prove the merchant answers a one time challenge. Sandbox: nothing to prove. */
    public CallbackRegistration verify(CommercialContext ctx, String id) {
        CallbackRegistration r = get(ctx, id);
        if (ctx.environment() == Environment.SANDBOX) return r;
        if (r.getStatus() == RegistrationStatus.ACTIVE) throw CommercialException.invalidState("The registration is already active");
        if (r.getStatus() != RegistrationStatus.PENDING_VERIFICATION) {
            throw CommercialException.invalidState("A " + r.getStatus().name().toLowerCase(Locale.ROOT) + " registration cannot be verified");
        }
        String challenge = Ids.next("ch");
        String failure = developerService.verifyCallback(r.getUrl(), challenge, r.getId(), true);
        if (failure != null) {
            throw new CommercialException(ErrorCode.CALLBACK_NOT_VERIFIED, "The URL did not confirm the challenge").with("detail", failure);
        }
        Instant now = Instant.now();
        r.setStatus(RegistrationStatus.ACTIVE);
        r.setStatusReason(null);
        r.setVerifiedAt(now);
        r.setUpdatedAt(now);
        return registrations.save(r);
    }

    /** Queues a callback.test event (or a sample of a real type) for this registration alone. */
    public Map<String, Object> test(CommercialContext ctx, String id, TestEventRequest req) {
        CallbackRegistration r = get(ctx, id);
        if (r.getType() != RegistrationType.EVENT) {
            throw CommercialException.invalidState("Only event registrations receive test events");
        }
        if (r.getStatus() != RegistrationStatus.ACTIVE) {
            throw CommercialException.invalidState("The registration is not active");
        }
        String type = req == null || req.eventType() == null || req.eventType().isBlank() ? EventTypes.CALLBACK_TEST : req.eventType();
        EventService.requireConcreteType(type);
        CommercialEvent e = events.emitDirect(type, ctx.merchantId(), ctx.appId(), ctx.livemode(), r.getId(),
                sampleData(type, r.getId()), List.of(new EventTarget(r.getUrl(), "REGISTERED", r.getId())));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("eventId", e.getEventId());
        out.put("registrationId", r.getId());
        return out;
    }

    /** Developer service only: disable a failing endpoint or suspend an abusive one, or re-enable a verified one. */
    public CallbackRegistration changeStatus(CommercialContext ctx, String id, RegistrationStatusRequest req) {
        CallbackRegistration r = get(ctx, id);
        RegistrationStatus target = req.status();
        if (target == RegistrationStatus.PENDING_VERIFICATION) {
            throw CommercialException.validation("status", "must be ACTIVE, DISABLED or SUSPENDED");
        }
        if (target != RegistrationStatus.ACTIVE && (req.reason() == null || req.reason().isBlank())) {
            throw CommercialException.validation("reason", "is required when disabling or suspending");
        }
        if (target == RegistrationStatus.ACTIVE && r.getVerifiedAt() == null) {
            throw CommercialException.invalidState("The registration has not been verified");
        }
        r.setStatus(target);
        r.setStatusReason(target == RegistrationStatus.ACTIVE ? null : req.reason());
        r.setUpdatedAt(Instant.now());
        return registrations.save(r);
    }

    static Map<String, Object> sampleData(String type, String objectId) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("sample", true);
        d.put("type", type);
        d.put("id", objectId);
        return d;
    }

    // Validation

    private static String requireUrl(String url, Environment env) {
        String reason = CallbackUrlValidator.check(url, env).orElse(null);
        if (reason != null) throw new CommercialException(ErrorCode.INVALID_CALLBACK_URL, reason);
        return url.trim();
    }

    private static List<String> eventTypesFor(RegistrationType type, List<String> requested, boolean creating) {
        if (type == RegistrationType.VALIDATION) {
            if (requested != null && !requested.isEmpty() && !requested.equals(List.of(VALIDATION_EVENT))) {
                throw CommercialException.validation("eventTypes", "a validation registration uses only " + VALIDATION_EVENT);
            }
            return new ArrayList<>(List.of(VALIDATION_EVENT));
        }
        if (requested == null || requested.isEmpty()) {
            if (creating) throw CommercialException.validation("eventTypes", "at least one event type or pattern is required");
            throw CommercialException.validation("eventTypes", "must not be empty");
        }
        List<String> out = new ArrayList<>();
        for (String t : requested) {
            if (!EventTypeMatcher.isValidPattern(t)) {
                throw CommercialException.validation("eventTypes", "'" + t + "' is not a valid event type or pattern");
            }
            if (!out.contains(t)) out.add(t);
        }
        return out;
    }

    private static String onTimeoutFor(RegistrationType type, String requested, String fallback) {
        if (requested == null || requested.isBlank()) return type == RegistrationType.VALIDATION ? fallback : null;
        if (type != RegistrationType.VALIDATION) {
            throw CommercialException.validation("onTimeout", "applies to validation registrations only");
        }
        String v = requested.toUpperCase(Locale.ROOT);
        if (!v.equals("ACCEPT") && !v.equals("REJECT")) throw CommercialException.validation("onTimeout", "must be ACCEPT or REJECT");
        return v;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String field) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw CommercialException.validation(field, "is not a valid value");
        }
    }
}
