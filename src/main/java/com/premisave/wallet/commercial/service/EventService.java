package com.premisave.wallet.commercial.service;

import com.premisave.wallet.commercial.entity.CallbackRegistration;
import com.premisave.wallet.commercial.entity.CommercialEvent;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.entity.EventTarget;
import com.premisave.wallet.commercial.enums.ForwardStatus;
import com.premisave.wallet.commercial.enums.RegistrationStatus;
import com.premisave.wallet.commercial.enums.RegistrationType;
import com.premisave.wallet.commercial.repository.CallbackRegistrationRepository;
import com.premisave.wallet.commercial.repository.CommercialEventRepository;
import com.premisave.wallet.commercial.dto.SettlementViews;
import com.premisave.wallet.commercial.dto.Views;
import com.premisave.wallet.commercial.util.EventTypeMatcher;
import com.premisave.wallet.commercial.util.Hashing;
import com.premisave.wallet.commercial.util.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Writes events to the outbox and works out where each one goes. A per request
 * callbackUrl replaces the registered URLs unless the request also asked for
 * registered ones (notifyRegistered). Delivery itself belongs to
 * EventDispatcher and the developer service.
 */
@Service("commercialEventService")
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final CommercialEventRepository events;
    private final CallbackRegistrationRepository registrations;

    public EventService(CommercialEventRepository events, CallbackRegistrationRepository registrations) {
        this.events = events;
        this.registrations = registrations;
    }

    /**
     * Emits the event for a transaction's current status. The event id is derived from the
     * transaction, event type and version, so emitting the same change twice (for example
     * during recovery) creates one event.
     */
    public void emit(CommercialTransaction tx) {
        String type = EventTypes.forTransaction(tx);
        if (type == null) return;
        // Items of a bulk payout that was rolled back before it was accepted were never visible to the merchant.
        if ("true".equals(tx.getRelations().get("silent"))) return;
        String id = "evt_" + Hashing.sha256Hex((tx.getId() + "|" + type + "|" + tx.getVersion()).getBytes()).substring(0, 16);
        List<EventTarget> targets = targetsFor(tx.getAppId(), tx.isLivemode(), type, tx.getCallbackUrl(), tx.isNotifyRegistered());
        store(id, type, tx.getMerchantId(), tx.getAppId(), tx.isLivemode(), tx.getId(), objectView(tx), targets);
    }

    /** Emits a one-off event (tests, verification) to the given targets. */
    public CommercialEvent emitDirect(String type, String merchantId, String appId, boolean livemode,
                                      String objectId, Map<String, Object> data, List<EventTarget> targets) {
        return store(Ids.next("evt"), type, merchantId, appId, livemode, objectId, data, targets);
    }

    /**
     * Emits an event under an id the caller chose, so asking twice for the same thing (for example
     * after a crash) stores one event. Used for events that belong to no single transaction.
     */
    public CommercialEvent emitWithId(String eventId, String type, String merchantId, String appId, boolean livemode,
                                      String objectId, Map<String, Object> data, List<EventTarget> targets) {
        return store(eventId, type, merchantId, appId, livemode, objectId, data, targets);
    }

    /** Re-queues an event for delivery, optionally to a different set of targets. */
    public void requeue(CommercialEvent event, List<EventTarget> replacementTargets) {
        if (replacementTargets != null && !replacementTargets.isEmpty()) event.setTargets(replacementTargets);
        event.setForwardStatus(event.getTargets().isEmpty() ? ForwardStatus.NOT_REQUIRED : ForwardStatus.PENDING);
        event.setNextAttemptAt(Instant.now());
        events.save(event);
    }

    public List<EventTarget> targetsFor(String appId, boolean livemode, String eventType, String callbackUrl,
                                        boolean notifyRegistered) {
        List<EventTarget> targets = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        boolean hasRequestUrl = callbackUrl != null && !callbackUrl.isBlank();
        if (hasRequestUrl) {
            targets.add(new EventTarget(callbackUrl, "REQUEST", null));
            seen.add(callbackUrl);
        }
        if (!hasRequestUrl || notifyRegistered) {
            for (CallbackRegistration r : registrations.findByAppIdAndLivemodeAndTypeAndStatus(
                    appId, livemode, RegistrationType.EVENT, RegistrationStatus.ACTIVE)) {
                if (EventTypeMatcher.matchesAny(r.getEventTypes(), eventType) && seen.add(r.getUrl())) {
                    targets.add(new EventTarget(r.getUrl(), "REGISTERED", r.getId()));
                }
            }
        }
        return targets;
    }

    /** Rejects patterns; sample and test events need a concrete event type. */
    public static void requireConcreteType(String type) {
        if (type == null || type.contains("*") || !EventTypeMatcher.isValidPattern(type)) {
            throw CommercialException.validation("eventType", "must be a concrete event type such as payment.succeeded");
        }
    }

    private Map<String, Object> objectView(CommercialTransaction tx) {
        return switch (tx.getType()) {
            case PAYMENT -> Views.payment(tx);
            case PAYOUT -> Views.payout(tx);
            case SETTLEMENT -> SettlementViews.settlement(tx);
            case REFUND -> com.premisave.wallet.commercial.refund.RefundViews.refund(tx);
            default -> Views.transaction(tx);
        };
    }

    private CommercialEvent store(String id, String type, String merchantId, String appId, boolean livemode,
                                  String objectId, Map<String, Object> data, List<EventTarget> targets) {
        CommercialEvent e = new CommercialEvent();
        e.setEventId(id);
        e.setType(type);
        e.setMerchantId(merchantId);
        e.setAppId(appId);
        e.setLivemode(livemode);
        e.setObjectId(objectId);
        e.setData(data);
        e.setTargets(targets);
        e.setForwardStatus(targets.isEmpty() ? ForwardStatus.NOT_REQUIRED : ForwardStatus.PENDING);
        e.setNextAttemptAt(Instant.now());
        e.setCreatedAt(Instant.now());
        try {
            return events.insert(e);
        } catch (DuplicateKeyException already) {
            log.debug("Event {} already recorded", id);
            Optional<CommercialEvent> existing = events.findById(id);
            return existing.orElse(e);
        }
    }
}
