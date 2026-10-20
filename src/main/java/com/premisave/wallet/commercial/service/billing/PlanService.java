package com.premisave.wallet.commercial.service.billing;

import com.premisave.wallet.commercial.config.properties.CommercialProperties;
import com.premisave.wallet.commercial.dto.billing.BillingViews;
import com.premisave.wallet.commercial.dto.billing.CreatePlanRequest;
import com.premisave.wallet.commercial.dto.billing.UpdatePlanRequest;
import com.premisave.wallet.commercial.entity.billing.Plan;
import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.repository.billing.BillingPlanRepository;
import com.premisave.wallet.commercial.security.CommercialContext;
import com.premisave.wallet.commercial.service.CommercialIdempotencyService;
import com.premisave.wallet.commercial.util.Ids;
import com.premisave.wallet.commercial.util.RequestValidators;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Plans (sections 8.1 to 8.4). */
@Service("commercialPlanService")
public class PlanService {

    private final CommercialIdempotencyService idempotency;
    private final BillingPlanRepository plans;
    private final MongoTemplate mongo;
    private final CommercialProperties props;

    public PlanService(CommercialIdempotencyService idempotency, BillingPlanRepository plans, MongoTemplate mongo,
                       CommercialProperties props) {
        this.idempotency = idempotency;
        this.plans = plans;
        this.mongo = mongo;
        this.props = props;
    }

    public CommercialIdempotencyService.Outcome<Map<String, Object>> create(CommercialContext ctx, CreatePlanRequest req,
                                                                   String idempotencyKey, String bodyHash) {
        return idempotency.execute(ctx, idempotencyKey, bodyHash, "PLAN",
                () -> doCreate(ctx, req),
                id -> BillingViews.plan(plans.findById(id).orElseThrow(() -> CommercialException.notFound("Plan"))));
    }

    private String doCreate(CommercialContext ctx, CreatePlanRequest req) {
        String planId = PlanRules.planId(req.planId());
        if (planId == null) planId = Ids.next("plan");

        Plan p = new Plan();
        p.setId(Ids.next("plandoc"));
        p.setPlanId(planId);
        p.setMerchantId(ctx.merchantId());
        p.setLivemode(ctx.livemode());
        p.setName(req.name().trim());
        p.setAmount(PlanRules.price("amount", req.amount(), false));
        String currency = PlanRules.currency(req.currency());
        if (!props.supportsCurrency(currency)) {
            throw CommercialException.validation("currency", "is not supported; use one of " + String.join(", ", props.currencies()));
        }
        p.setCurrency(currency);
        p.setInterval(req.interval());
        p.setIntervalCount(PlanRules.intervalCount(req.interval(), req.intervalCount()));
        p.setTrialDays(PlanRules.trialDays(req.trialDays()));
        p.setSetupFee(PlanRules.price("setupFee", req.setupFee() == null ? java.math.BigDecimal.ZERO : req.setupFee(), true));
        p.setAllowedMethods(PlanRules.methods(req.allowedMethods()));
        p.setRetryScheduleDays(PlanRules.retrySchedule(req.retryScheduleDays()));
        p.setOnExhausted(req.onExhausted() == null ? Plan.OnExhausted.CANCEL : req.onExhausted());
        p.setMetadata(RequestValidators.metadata(req.metadata()));
        Instant now = Instant.now();
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        try {
            plans.insert(p);
        } catch (DuplicateKeyException e) {
            throw new CommercialException(ErrorCode.DUPLICATE_REQUEST, "A plan with planId " + planId + " already exists")
                    .with("planId", planId);
        }
        return p.getId();
    }

    public Page<Plan> list(CommercialContext ctx, Map<String, String> f, Pageable pageable) {
        List<Criteria> all = new ArrayList<>();
        all.add(Criteria.where("merchantId").is(ctx.merchantId()));
        all.add(Criteria.where("livemode").is(ctx.livemode()));
        String status = f.get("status");
        if (status != null && !status.isBlank()) {
            try {
                all.add(Criteria.where("status").is(Plan.Status.valueOf(status.trim().toUpperCase(Locale.ROOT)).name()));
            } catch (IllegalArgumentException e) {
                throw CommercialException.validation("status", "must be ACTIVE or ARCHIVED");
            }
        }
        Query q = Query.query(new Criteria().andOperator(all.toArray(new Criteria[0])));
        long total = mongo.count(q, Plan.class);
        return new org.springframework.data.domain.PageImpl<>(mongo.find(q.with(pageable), Plan.class), pageable, total);
    }

    public Plan require(CommercialContext ctx, String planId) {
        return plans.findByPlanIdAndMerchantIdAndLivemode(planId, ctx.merchantId(), ctx.livemode())
                .orElseThrow(() -> CommercialException.notFound("Plan"));
    }

    public java.util.Optional<Plan> findByPlanId(CommercialContext ctx, String planId) {
        return plans.findByPlanIdAndMerchantIdAndLivemode(planId, ctx.merchantId(), ctx.livemode());
    }

    public Map<String, Object> get(CommercialContext ctx, String planId) {
        return BillingViews.plan(require(ctx, planId));
    }

    public Map<String, Object> update(CommercialContext ctx, String planId, UpdatePlanRequest req) {
        Plan p = require(ctx, planId);
        refuseMoneyChange(p, req);
        if (req.name() != null) {
            if (req.name().isBlank()) throw CommercialException.validation("name", "must not be blank");
            p.setName(req.name().trim());
        }
        if (req.status() != null) p.setStatus(req.status());
        if (req.allowedMethods() != null) p.setAllowedMethods(PlanRules.methods(req.allowedMethods()));
        if (req.retryScheduleDays() != null) p.setRetryScheduleDays(PlanRules.retrySchedule(req.retryScheduleDays()));
        if (req.onExhausted() != null) p.setOnExhausted(req.onExhausted());
        if (req.metadata() != null) p.setMetadata(RequestValidators.metadata(req.metadata()));
        p.setUpdatedAt(Instant.now());
        return BillingViews.plan(plans.save(p));
    }

    private static void refuseMoneyChange(Plan p, UpdatePlanRequest req) {
        boolean changes =
                (req.amount() != null && req.amount().compareTo(p.getAmount()) != 0)
                || (req.currency() != null && !req.currency().isBlank() && !req.currency().equalsIgnoreCase(p.getCurrency()))
                || (req.interval() != null && req.interval() != p.getInterval())
                || (req.intervalCount() != null && req.intervalCount() != p.getIntervalCount())
                || (req.trialDays() != null && req.trialDays() != p.getTrialDays())
                || (req.setupFee() != null && p.getSetupFee() != null && req.setupFee().compareTo(p.getSetupFee()) != 0);
        if (changes) {
            throw CommercialException.invalidState("Price, currency, interval, trial and setup fee cannot change; create a new plan instead");
        }
    }
}
