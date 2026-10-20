package com.premisave.wallet.commercial.service.billing;

import com.premisave.wallet.commercial.dto.billing.BillingViews;
import com.premisave.wallet.commercial.dto.billing.CancelSubscriptionRequest;
import com.premisave.wallet.commercial.dto.billing.CreateSubscriptionRequest;
import com.premisave.wallet.commercial.dto.billing.PauseSubscriptionRequest;
import com.premisave.wallet.commercial.dto.billing.ResumeSubscriptionRequest;
import com.premisave.wallet.commercial.dto.billing.UpdateSubscriptionRequest;
import com.premisave.wallet.commercial.entity.billing.BillingCustomer;
import com.premisave.wallet.commercial.entity.billing.CustomerPaymentMethod;
import com.premisave.wallet.commercial.entity.billing.Plan;
import com.premisave.wallet.commercial.entity.billing.Subscription;
import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.repository.billing.BillingSubscriptionRepository;
import com.premisave.wallet.commercial.repository.billing.CustomerPaymentMethodRepository;
import com.premisave.wallet.commercial.security.CommercialContext;
import com.premisave.wallet.commercial.service.CommercialIdempotencyService;
import com.premisave.wallet.commercial.service.CommercialPaymentService;
import com.premisave.wallet.commercial.service.EventTypes;
import com.premisave.wallet.commercial.service.MethodPolicy;
import com.premisave.wallet.commercial.util.Ids;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Subscriptions (sections 8.9 to 8.15). Every change after creation is a guarded update on the version
 * (or on the state it expects), so a pause racing a billing run, or two merchants' calls, cannot overwrite
 * each other: the loser gets a clear 409 and can look again.
 */
@Service("commercialSubscriptionService")
public class SubscriptionService implements PaymentMethodListener {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);
    private static final Duration START_TOLERANCE = Duration.ofMinutes(5);
    private static final Duration MAX_AHEAD = Duration.ofDays(366);

    private final CommercialIdempotencyService idempotency;
    private final BillingSubscriptionRepository subs;
    private final CustomerService customers;
    private final PlanService plans;
    private final CustomerPaymentMethodRepository methods;
    private final BillingEngine engine;
    private final SubscriptionTransitions transitions;
    private final BillingEvents events;
    private final MongoTemplate mongo;

    public SubscriptionService(CommercialIdempotencyService idempotency, BillingSubscriptionRepository subs,
                               CustomerService customers, PlanService plans, CustomerPaymentMethodRepository methods,
                               BillingEngine engine, SubscriptionTransitions transitions, BillingEvents events,
                               MongoTemplate mongo) {
        this.idempotency = idempotency;
        this.subs = subs;
        this.customers = customers;
        this.plans = plans;
        this.methods = methods;
        this.engine = engine;
        this.transitions = transitions;
        this.events = events;
        this.mongo = mongo;
    }

    // Create

    public CommercialIdempotencyService.Outcome<Map<String, Object>> create(CommercialContext ctx, CreateSubscriptionRequest req,
                                                                   String idempotencyKey, String bodyHash) {
        return idempotency.execute(ctx, idempotencyKey, bodyHash, "SUBSCRIPTION",
                () -> doCreate(ctx, req),
                id -> BillingViews.subscription(subs.findById(id).orElseThrow(() -> CommercialException.notFound("Subscription"))));
    }

    private String doCreate(CommercialContext ctx, CreateSubscriptionRequest req) {
        BillingCustomer customer = customers.require(ctx, req.customerId().trim());
        Plan plan = plans.require(ctx, req.planId().trim());
        if (plan.getStatus() != Plan.Status.ACTIVE) {
            throw CommercialException.invalidState("Plan " + plan.getPlanId() + " is archived and takes no new subscriptions");
        }
        CustomerPaymentMethod pm = resolveMethod(ctx, customer, req.paymentMethodId());
        checkMethodAllowed(ctx, plan.getAllowedMethods(), pm);

        Instant now = Instant.now();
        Instant start = BillingInput.instant("startAt", req.startAt());
        if (start == null) start = now;
        if (start.isBefore(now.minus(START_TOLERANCE))) throw CommercialException.validation("startAt", "must not be in the past");
        if (start.isAfter(now.plus(MAX_AHEAD))) throw CommercialException.validation("startAt", "must be within a year from now");
        if (start.isBefore(now)) start = now;
        int trialDays = PlanRules.trialDays(req.trialDays() != null ? req.trialDays() : plan.getTrialDays());
        CommercialPaymentService.checkCallbackUrl(ctx, req.callbackUrl());

        Subscription s = new Subscription();
        s.setId(Ids.next("sub"));
        s.setMerchantId(ctx.merchantId());
        s.setAppId(ctx.appId());
        s.setLivemode(ctx.livemode());
        s.setCustomerId(customer.getCustomerId());
        s.setPlanId(plan.getPlanId());
        s.setPaymentMethodId(pm.getPaymentMethodId());
        s.setQuantity(req.quantity() == null ? 1 : req.quantity());
        s.setTerms(Subscription.Terms.of(plan));
        s.setTrialDays(trialDays);
        s.setRequestedStartAt(start);
        s.setCallbackUrl(BillingInput.blankToNull(req.callbackUrl()));
        s.setMetadata(BillingInput.subscriptionMetadata(req.metadata()));
        s.setActiveKey(Subscription.activeKeyFor(ctx.merchantId(), ctx.livemode(), customer.getCustomerId(), plan.getPlanId()));
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        if (pm.getStatus() == CustomerPaymentMethod.Status.ACTIVE) {
            SubscriptionRules.Schedule sc = SubscriptionRules.initialSchedule(start, trialDays,
                    plan.getInterval(), plan.getIntervalCount());
            s.setState(sc.state());
            s.setTrialEnd(sc.trialEnd());
            s.setAnchor(sc.anchor());
            s.setPeriodStart(sc.periodStart());
            s.setPeriodEnd(sc.periodEnd());
            s.setNextBillingAt(sc.nextBillingAt());
        } else {
            s.setState(Subscription.State.PENDING_AUTHORISATION);
        }
        try {
            subs.insert(s);
        } catch (DuplicateKeyException e) {
            CommercialException ex = new CommercialException(ErrorCode.DUPLICATE_REQUEST,
                    "This customer already has a live subscription to plan " + plan.getPlanId());
            subs.findByActiveKey(s.getActiveKey()).ifPresent(x -> ex.with("subscriptionId", x.getId()));
            throw ex;
        }
        events.subscription(EventTypes.SUBSCRIPTION_CREATED, s);
        if (s.getState() != Subscription.State.PENDING_AUTHORISATION) {
            events.subscription(EventTypes.SUBSCRIPTION_ACTIVATED, s);
            billIfDue(s, now);
        }
        return s.getId();
    }

    private CustomerPaymentMethod resolveMethod(CommercialContext ctx, BillingCustomer customer, String requestedId) {
        CustomerPaymentMethod pm;
        if (requestedId != null && !requestedId.isBlank()) {
            pm = methods.findByPaymentMethodIdAndMerchantIdAndLivemode(requestedId.trim(), ctx.merchantId(), ctx.livemode())
                    .filter(m -> m.getCustomerId().equals(customer.getCustomerId()))
                    .orElseThrow(() -> CommercialException.notFound("Payment method"));
        } else {
            pm = methods.findByCustomerId(customer.getCustomerId()).stream()
                    .filter(m -> m.isDefaultMethod() && usable(m)).findFirst()
                    .orElseThrow(() -> CommercialException.validation("paymentMethodId", "the customer has no default payment method; add one first"));
        }
        if (!usable(pm)) {
            throw new CommercialException(ErrorCode.METHOD_UNAVAILABLE, "That payment method is " + pm.getStatus() + " and cannot be used");
        }
        return pm;
    }

    private static boolean usable(CustomerPaymentMethod m) {
        return m.getStatus() == CustomerPaymentMethod.Status.ACTIVE || m.getStatus() == CustomerPaymentMethod.Status.PENDING_AUTHORISATION;
    }

    private static void checkMethodAllowed(CommercialContext ctx, java.util.Set<com.premisave.wallet.commercial.enums.MethodType> allowed,
                                           CustomerPaymentMethod pm) {
        if (!allowed.contains(pm.getType())) {
            throw new CommercialException(ErrorCode.METHOD_NOT_ENABLED, "This plan does not accept " + pm.getType());
        }
        if (!MethodPolicy.isEnabled(ctx.merchant(), ctx.app(), ctx.environment(), pm.getType())) {
            throw new CommercialException(ErrorCode.METHOD_NOT_ENABLED, pm.getType() + " is not enabled for this merchant or app");
        }
    }

    /** The first cycle runs at once when it is due, but a failure here never undoes the subscription. */
    private void billIfDue(Subscription s, Instant now) {
        if (s.getNextBillingAt() == null || s.getNextBillingAt().isAfter(now)) return;
        try {
            engine.billDue(s.getId(), now);
        } catch (RuntimeException e) {
            log.error("First billing of subscription {} failed; the scheduler will retry", s.getId(), e);
        }
    }

    // Read

    public Page<Subscription> list(CommercialContext ctx, Map<String, String> f, Pageable pageable) {
        List<Criteria> all = new ArrayList<>();
        all.add(Criteria.where("merchantId").is(ctx.merchantId()));
        all.add(Criteria.where("livemode").is(ctx.livemode()));
        String state = f.get("state");
        if (state != null && !state.isBlank()) {
            try {
                all.add(Criteria.where("state").is(Subscription.State.valueOf(state.trim().toUpperCase(Locale.ROOT)).name()));
            } catch (IllegalArgumentException e) {
                throw CommercialException.validation("state", "is not a subscription state");
            }
        }
        String customerId = f.get("customerId");
        if (customerId != null && !customerId.isBlank()) all.add(Criteria.where("customerId").is(customerId.trim()));
        String planId = f.get("planId");
        if (planId != null && !planId.isBlank()) all.add(Criteria.where("planId").is(planId.trim()));
        Instant from = BillingInput.instant("from", f.get("from"));
        Instant to = BillingInput.instant("to", f.get("to"));
        if (from != null) all.add(Criteria.where("createdAt").gte(from));
        if (to != null) all.add(Criteria.where("createdAt").lte(to));
        Query q = Query.query(new Criteria().andOperator(all.toArray(new Criteria[0])));
        long total = mongo.count(q, Subscription.class);
        return new PageImpl<>(mongo.find(q.with(pageable), Subscription.class), pageable, total);
    }

    public Subscription require(CommercialContext ctx, String id) {
        return subs.findByIdAndMerchantIdAndLivemode(id, ctx.merchantId(), ctx.livemode())
                .orElseThrow(() -> CommercialException.notFound("Subscription"));
    }

    public Map<String, Object> get(CommercialContext ctx, String id) {
        return BillingViews.subscription(require(ctx, id));
    }

    // Pause, resume, cancel

    public Map<String, Object> pause(CommercialContext ctx, String id, PauseSubscriptionRequest req) {
        Subscription s = require(ctx, id);
        if (!SubscriptionRules.canPause(s.getState())) {
            throw CommercialException.invalidState("A " + s.getState() + " subscription cannot be paused; only ACTIVE, TRIALING or PAST_DUE can");
        }
        Instant now = Instant.now();
        Instant resumeAt = BillingInput.instant("resumeAt", req == null ? null : req.resumeAt());
        if (resumeAt != null && !resumeAt.isAfter(now)) throw CommercialException.validation("resumeAt", "must be in the future");
        if (resumeAt != null && resumeAt.isAfter(now.plus(MAX_AHEAD))) throw CommercialException.validation("resumeAt", "must be within a year from now");
        Document set = new Document("state", Subscription.State.PAUSED.name())
                .append("statePrePause", s.getState().name())
                .append("pausedAt", Date.from(now))
                .append("pauseReason", req == null ? null : BillingInput.blankToNull(req.reason()));
        Document unset = new Document();
        if (resumeAt != null) set.append("resumeAt", Date.from(resumeAt)); else unset.append("resumeAt", "");
        Subscription after = transitions.apply(id, Criteria.where("version").is(s.getVersion()), set, unset)
                .orElseThrow(() -> changedMeanwhile());
        events.subscription(EventTypes.SUBSCRIPTION_PAUSED, after);
        return BillingViews.subscription(after);
    }

    public Map<String, Object> resume(CommercialContext ctx, String id, ResumeSubscriptionRequest req) {
        Subscription s = require(ctx, id);
        if (!SubscriptionRules.canResume(s.getState())) {
            throw CommercialException.invalidState("Only a PAUSED subscription can be resumed; this one is " + s.getState());
        }
        return BillingViews.subscription(resumeNow(s, req != null && Boolean.TRUE.equals(req.billNow()), Instant.now()));
    }

    /** Resumes paused subscriptions whose resumeAt has come. Returns how many were resumed. */
    public int resumeDue(Instant now, int limit) {
        List<Subscription> due = mongo.find(Query.query(Criteria.where("state").is(Subscription.State.PAUSED.name())
                .and("resumeAt").lte(now)).limit(limit), Subscription.class);
        int n = 0;
        for (Subscription s : due) {
            try {
                resumeNow(s, false, now);
                n++;
            } catch (RuntimeException e) {
                log.warn("Subscription {} could not be resumed automatically: {}", s.getId(), e.getMessage());
            }
        }
        return n;
    }

    /** Resumes one paused subscription whose resumeAt has come, as of the given time (the sandbox clock uses this). */
    public void resumeScheduled(String id, Instant now) {
        Subscription s = subs.findById(id).orElse(null);
        if (s == null || s.getState() != Subscription.State.PAUSED || s.getResumeAt() == null || s.getResumeAt().isAfter(now)) return;
        resumeNow(s, false, now);
    }

    private Subscription resumeNow(Subscription s, boolean billNow, Instant now) {
        if (billNow && s.getStatePrePause() == Subscription.State.PAST_DUE) {
            throw CommercialException.invalidState("This subscription has an unpaid invoice; retry the invoice instead of billing a new period");
        }
        SubscriptionRules.Resumed r = billNow ? SubscriptionRules.resumeAndBill(s, now) : SubscriptionRules.resumeWithoutBilling(s, now);
        Document set = new Document("state", r.state().name())
                .append("generation", r.generation())
                .append("periodIndex", r.periodIndex())
                .append("upcomingNotified", false);
        if (r.anchor() != null) set.append("anchor", Date.from(r.anchor()));
        if (r.periodStart() != null) set.append("periodStart", Date.from(r.periodStart()));
        if (r.periodEnd() != null) set.append("periodEnd", Date.from(r.periodEnd()));
        if (r.nextBillingAt() != null) set.append("nextBillingAt", Date.from(r.nextBillingAt()));
        Document unset = new Document("pausedAt", "").append("resumeAt", "").append("pauseReason", "").append("statePrePause", "");
        Optional<Subscription> after = transitions.apply(s.getId(),
                Criteria.where("version").is(s.getVersion()).and("state").is(Subscription.State.PAUSED.name()), set, unset);
        if (after.isEmpty()) {
            Subscription now2 = subs.findById(s.getId()).orElseThrow(() -> CommercialException.notFound("Subscription"));
            if (now2.getState() != Subscription.State.PAUSED) return now2;
            throw changedMeanwhile();
        }
        events.subscription(EventTypes.SUBSCRIPTION_ACTIVATED, after.get());
        if (billNow) billIfDue(after.get(), now);
        return subs.findById(s.getId()).orElse(after.get());
    }

    public Map<String, Object> cancel(CommercialContext ctx, String id, CancelSubscriptionRequest req) {
        Subscription s = require(ctx, id);
        if (!SubscriptionRules.canCancel(s.getState())) {
            throw CommercialException.invalidState("This subscription is already " + s.getState());
        }
        String reason = req == null ? null : BillingInput.blankToNull(req.reason());
        boolean atEnd = req != null && Boolean.TRUE.equals(req.atPeriodEnd());
        if (atEnd && SubscriptionRules.canCancelAtPeriodEnd(s.getState())) {
            if (s.isCancelAtPeriodEnd()) return BillingViews.subscription(s);
            Document set = new Document("cancelAtPeriodEnd", true).append("cancelReason", reason);
            Subscription after = transitions.apply(id, Criteria.where("version").is(s.getVersion()), set, null)
                    .orElseThrow(() -> changedMeanwhile());
            return BillingViews.subscription(after);
        }
        Optional<Subscription> ended = transitions.terminate(id, Subscription.State.CANCELLED, reason, Instant.now());
        if (ended.isEmpty()) {
            Subscription current = subs.findById(id).orElseThrow(() -> CommercialException.notFound("Subscription"));
            if (current.getState().isTerminal()) throw CommercialException.invalidState("This subscription is already " + current.getState());
            throw changedMeanwhile();
        }
        return BillingViews.subscription(ended.get());
    }

    // Update

    public Map<String, Object> update(CommercialContext ctx, String id, UpdateSubscriptionRequest req) {
        Subscription s = require(ctx, id);
        if (!SubscriptionRules.canUpdate(s.getState())) {
            throw CommercialException.invalidState("A " + s.getState() + " subscription cannot be changed");
        }
        Document set = new Document();
        Document unset = new Document();
        if (req.quantity() != null) set.append("quantity", req.quantity());
        if (req.callbackUrl() != null) {
            CommercialPaymentService.checkCallbackUrl(ctx, req.callbackUrl());
            String url = BillingInput.blankToNull(req.callbackUrl());
            if (url == null) unset.append("callbackUrl", ""); else set.append("callbackUrl", url);
        }
        if (req.metadata() != null) set.append("metadata", BillingInput.subscriptionMetadata(req.metadata()));

        CustomerPaymentMethod newMethod = null;
        if (req.paymentMethodId() != null && !req.paymentMethodId().isBlank()
                && !req.paymentMethodId().trim().equals(s.getPaymentMethodId())) {
            CustomerPaymentMethod found = methods.findByPaymentMethodIdAndMerchantIdAndLivemode(req.paymentMethodId().trim(), ctx.merchantId(), ctx.livemode())
                    .filter(m -> m.getCustomerId().equals(s.getCustomerId()))
                    .orElseThrow(() -> CommercialException.notFound("Payment method"));
            if (!usable(found)) {
                throw new CommercialException(ErrorCode.METHOD_UNAVAILABLE, "That payment method is " + found.getStatus() + " and cannot be used");
            }
            Plan currentPlan = plans.findByPlanId(ctx, s.getPlanId()).orElse(null);
            if (currentPlan != null) checkMethodAllowed(ctx, currentPlan.getAllowedMethods(), found);
            set.append("paymentMethodId", found.getPaymentMethodId());
            newMethod = found;
        }

        String target = s.getPendingPlanId() != null ? s.getPendingPlanId() : s.getPlanId();
        if (req.planId() != null && !req.planId().isBlank() && !req.planId().trim().equals(target)) {
            switchPlan(ctx, s, req.planId().trim(), newMethod, set, unset);
        } else if (req.planId() != null && req.planId().trim().equals(s.getPlanId()) && s.getPendingTerms() != null) {
            unset.append("pendingTerms", "").append("pendingPlanId", "");
        }

        if (set.isEmpty() && unset.isEmpty()) return BillingViews.subscription(s);
        Subscription after;
        try {
            after = transitions.apply(id, Criteria.where("version").is(s.getVersion()), set, unset).orElseThrow(() -> changedMeanwhile());
        } catch (DuplicateKeyException e) {
            throw new CommercialException(ErrorCode.DUPLICATE_REQUEST, "The customer already has a live subscription to that plan");
        }
        if (newMethod != null && newMethod.getStatus() == CustomerPaymentMethod.Status.ACTIVE
                && after.getState() == Subscription.State.PENDING_AUTHORISATION) {
            activatePending(after);
            after = subs.findById(id).orElse(after);
        }
        return BillingViews.subscription(after);
    }

    private void switchPlan(CommercialContext ctx, Subscription s, String planId, CustomerPaymentMethod newMethod,
                            Document set, Document unset) {
        Plan plan = plans.require(ctx, planId);
        if (plan.getStatus() != Plan.Status.ACTIVE) {
            throw CommercialException.invalidState("Plan " + plan.getPlanId() + " is archived and cannot be switched to");
        }
        CustomerPaymentMethod current = newMethod != null ? newMethod
                : methods.findById(s.getPaymentMethodId()).orElseThrow(() -> CommercialException.notFound("Payment method"));
        checkMethodAllowed(ctx, plan.getAllowedMethods(), current);
        String newKey = Subscription.activeKeyFor(s.getMerchantId(), s.isLivemode(), s.getCustomerId(), plan.getPlanId());
        subs.findByActiveKey(newKey).filter(other -> !other.getId().equals(s.getId())).ifPresent(other -> {
            throw new CommercialException(ErrorCode.DUPLICATE_REQUEST, "The customer already has a live subscription to plan " + plan.getPlanId())
                    .with("subscriptionId", other.getId());
        });
        Subscription.Terms terms = Subscription.Terms.of(plan);
        Document doc = new Document();
        mongo.getConverter().write(terms, doc);
        doc.remove("_class");
        if (s.getState() == Subscription.State.PENDING_AUTHORISATION) {
            // Nothing has been billed, so the new plan simply replaces the old one.
            set.append("terms", doc).append("planId", plan.getPlanId()).append("activeKey", newKey);
        } else {
            set.append("pendingTerms", doc).append("pendingPlanId", plan.getPlanId());
        }
    }

    // Payment method listener

    @Override
    public void onActivated(CustomerPaymentMethod method) {
        for (Subscription s : waitingFor(method)) activatePending(s);
    }

    @Override
    public void onFailed(CustomerPaymentMethod method) {
        for (Subscription s : waitingFor(method)) {
            transitions.terminate(s.getId(), Subscription.State.CANCELLED,
                    "The payment method was not authorised", Instant.now());
        }
    }

    private List<Subscription> waitingFor(CustomerPaymentMethod method) {
        return mongo.find(Query.query(Criteria.where("paymentMethodId").is(method.getPaymentMethodId())
                .and("state").is(Subscription.State.PENDING_AUTHORISATION.name())).limit(200), Subscription.class);
    }

    /** Starts a subscription that was waiting for its payment method. */
    public void activatePending(Subscription s) {
        Instant now = Instant.now();
        Instant start = s.getRequestedStartAt() != null && s.getRequestedStartAt().isAfter(now) ? s.getRequestedStartAt() : now;
        Subscription.Terms t = s.getTerms();
        SubscriptionRules.Schedule sc = SubscriptionRules.initialSchedule(start, s.getTrialDays(), t.getInterval(), t.getIntervalCount());
        Document set = new Document("state", sc.state().name())
                .append("anchor", Date.from(sc.anchor()))
                .append("periodStart", Date.from(sc.periodStart()))
                .append("periodEnd", Date.from(sc.periodEnd()))
                .append("nextBillingAt", Date.from(sc.nextBillingAt()))
                .append("periodIndex", -1L)
                .append("upcomingNotified", false);
        if (sc.trialEnd() != null) set.append("trialEnd", Date.from(sc.trialEnd()));
        Optional<Subscription> after = transitions.apply(s.getId(),
                Criteria.where("state").is(Subscription.State.PENDING_AUTHORISATION.name()), set, null);
        after.ifPresent(a -> {
            events.subscription(EventTypes.SUBSCRIPTION_ACTIVATED, a);
            billIfDue(a, now);
        });
    }

    private static CommercialException changedMeanwhile() {
        return CommercialException.invalidState("The subscription was changed by another request; fetch it and try again");
    }
}
