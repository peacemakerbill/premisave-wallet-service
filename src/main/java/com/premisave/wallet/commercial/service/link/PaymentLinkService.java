package com.premisave.wallet.commercial.service.link;

import com.premisave.wallet.commercial.config.properties.CommercialLinksProperties;
import com.premisave.wallet.commercial.config.properties.CommercialProperties;
import com.premisave.wallet.commercial.dto.link.CreatePaymentLinkRequest;
import com.premisave.wallet.commercial.dto.link.LinkViews;
import com.premisave.wallet.commercial.dto.link.UpdatePaymentLinkRequest;
import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.entity.link.PaymentLink;
import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.enums.MethodType;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.enums.TxType;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.repository.link.PaymentLinkRepository;
import com.premisave.wallet.commercial.security.CommercialContext;
import com.premisave.wallet.commercial.service.CommercialIdempotencyService;
import com.premisave.wallet.commercial.service.MethodPolicy;
import com.premisave.wallet.commercial.service.TransactionFinishListener;
import com.premisave.wallet.commercial.util.CallbackUrlValidator;
import com.premisave.wallet.commercial.util.Ids;
import com.premisave.wallet.commercial.util.RequestValidators;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.BasicUpdate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Payment links (sections 5.5 to 5.8). A link holds the rules; each customer attempt is a normal
 * payment created through CommercialPaymentService, which asks this service to approve the attempt.
 */
@Service("commercialPaymentLinkService")
public class PaymentLinkService implements TransactionFinishListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentLinkService.class);
    private static final String RELATION = "paymentLinkId";
    private static final int PAYMENT_IDS_SHOWN = 100;

    private final CommercialIdempotencyService idempotency;
    private final PaymentLinkRepository links;
    private final MongoTemplate mongo;
    private final CommercialProperties props;
    private final CommercialLinksProperties linkProps;

    public PaymentLinkService(CommercialIdempotencyService idempotency, PaymentLinkRepository links, MongoTemplate mongo,
                              CommercialProperties props, CommercialLinksProperties linkProps) {
        this.idempotency = idempotency;
        this.links = links;
        this.mongo = mongo;
        this.props = props;
        this.linkProps = linkProps;
    }

    public CommercialIdempotencyService.Outcome<Map<String, Object>> create(CommercialContext ctx, CreatePaymentLinkRequest req,
                                                                   String idempotencyKey, String bodyHash) {
        return idempotency.execute(ctx, idempotencyKey, bodyHash, "PAYMENT_LINK",
                () -> doCreate(ctx, req),
                id -> view(requireOwn(ctx, id)));
    }

    private String doCreate(CommercialContext ctx, CreatePaymentLinkRequest req) {
        BigDecimal amount = scaled(req.amount());
        BigDecimal min = scaled(req.minAmount());
        BigDecimal max = scaled(req.maxAmount());
        PaymentLink.AmountType type = req.amountType() != null ? req.amountType()
                : (amount != null ? PaymentLink.AmountType.FIXED : PaymentLink.AmountType.OPEN);
        PaymentLinkRules.checkShape(type, amount, min, max);

        String currency = currencyOrDefault(ctx, req.currency());

        Set<MethodType> methods = req.allowedMethods() == null || req.allowedMethods().isEmpty()
                ? enabledMethods(ctx) : EnumSet.copyOf(req.allowedMethods());
        if (methods.isEmpty()) {
            throw new CommercialException(ErrorCode.METHOD_NOT_ENABLED, "No payment method is enabled for this merchant or app");
        }
        for (MethodType m : methods) {
            if (!MethodPolicy.isEnabled(ctx.merchant(), ctx.app(), ctx.environment(), m)) {
                throw new CommercialException(ErrorCode.METHOD_NOT_ENABLED, m + " is not enabled for this merchant or app");
            }
        }
        checkCallbackUrl(ctx, req.callbackUrl());

        Instant now = Instant.now();
        Instant expiresAt = parseFuture("expiresAt", req.expiresAt(), now);

        PaymentLink l = new PaymentLink();
        l.setId(Ids.next("plink"));
        l.setMerchantId(ctx.merchantId());
        l.setAppId(ctx.appId());
        l.setLivemode(ctx.livemode());
        l.setMerchantReference(req.merchantReference().trim());
        l.setDescription(req.description());
        l.setAmountType(type);
        l.setAmount(amount);
        l.setMinAmount(min);
        l.setMaxAmount(max);
        l.setCurrency(currency);
        l.setAllowedMethods(methods);
        l.setUsage(req.usage() != null ? req.usage() : PaymentLink.Usage.MULTIPLE);
        l.setCallbackUrl(blankToNull(req.callbackUrl()));
        l.setExpiresAt(expiresAt);
        l.setMetadata(RequestValidators.metadata(req.metadata()));
        l.setCreatedAt(now);
        l.setUpdatedAt(now);
        links.insert(l);
        return l.getId();
    }

    public Page<PaymentLink> list(CommercialContext ctx, Map<String, String> f, Pageable pageable) {
        Instant now = Instant.now();
        List<Criteria> all = new ArrayList<>();
        all.add(Criteria.where("merchantId").is(ctx.merchantId()));
        all.add(Criteria.where("livemode").is(ctx.livemode()));
        String ref = f.get("merchantReference");
        if (ref != null && !ref.isBlank()) all.add(Criteria.where("merchantReference").is(ref));
        Instant from = instant(f.get("from"), "from");
        Instant to = instant(f.get("to"), "to");
        if (from != null) all.add(Criteria.where("createdAt").gte(from));
        if (to != null) all.add(Criteria.where("createdAt").lte(to));

        String status = f.get("status");
        if (status != null && !status.isBlank()) {
            switch (status.trim().toUpperCase(Locale.ROOT)) {
                case PaymentLinkRules.ACTIVE -> {
                    all.add(Criteria.where("status").is(PaymentLink.Status.ACTIVE.name()));
                    all.add(new Criteria().orOperator(Criteria.where("expiresAt").is(null), Criteria.where("expiresAt").gt(now)));
                }
                case PaymentLinkRules.EXPIRED -> {
                    all.add(Criteria.where("status").is(PaymentLink.Status.ACTIVE.name()));
                    all.add(Criteria.where("expiresAt").lte(now));
                }
                case PaymentLinkRules.DISABLED -> all.add(Criteria.where("status").is(PaymentLink.Status.DISABLED.name()));
                case PaymentLinkRules.COMPLETED -> all.add(Criteria.where("status").is(PaymentLink.Status.COMPLETED.name()));
                default -> throw CommercialException.validation("status", "must be ACTIVE, DISABLED, EXPIRED or COMPLETED");
            }
        }
        Query q = Query.query(new Criteria().andOperator(all.toArray(new Criteria[0])));
        long total = mongo.count(q, PaymentLink.class);
        List<PaymentLink> page = mongo.find(q.with(pageable), PaymentLink.class);
        return new PageImpl<>(page, pageable, total);
    }

    public Map<String, Object> view(PaymentLink l) {
        return LinkViews.link(l, linkProps.urlFor(l.isLivemode(), l.getId()), Instant.now());
    }

    public Map<String, Object> detail(CommercialContext ctx, String id) {
        PaymentLink l = requireOwn(ctx, id);
        List<String> ids = mongo.find(Query.query(attempts(l.getId())).with(PageRequest.of(0, PAYMENT_IDS_SHOWN,
                Sort.by(Sort.Direction.DESC, "createdAt"))), CommercialTransaction.class).stream().map(CommercialTransaction::getId).toList();
        return LinkViews.linkDetail(l, linkProps.urlFor(l.isLivemode(), l.getId()), Instant.now(), successCount(l.getId()), ids);
    }

    public Map<String, Object> update(CommercialContext ctx, String id, UpdatePaymentLinkRequest req) {
        PaymentLink l = requireOwn(ctx, id);
        Instant now = Instant.now();

        boolean touchesMoney = req.amount() != null || req.minAmount() != null || req.maxAmount() != null || req.currency() != null;
        if (touchesMoney && mongo.count(Query.query(attempts(l.getId())), CommercialTransaction.class) > 0) {
            throw CommercialException.invalidState("amount and currency cannot change once a payment was attempted on this link");
        }
        if (req.description() != null) l.setDescription(req.description());
        if (req.status() != null) {
            switch (req.status().trim().toUpperCase(Locale.ROOT)) {
                case "ACTIVE" -> {
                    // A link that has been used up stays completed; only a disabled link is switched back on.
                    if (l.getStatus() == PaymentLink.Status.DISABLED) l.setStatus(PaymentLink.Status.ACTIVE);
                    else if (l.getStatus() == PaymentLink.Status.COMPLETED) {
                        throw CommercialException.invalidState("A completed payment link cannot be reactivated; create a new one");
                    }
                }
                case "DISABLED" -> l.setStatus(PaymentLink.Status.DISABLED);
                default -> throw CommercialException.validation("status", "can only be set to ACTIVE or DISABLED");
            }
        }
        if (req.expiresAt() != null) l.setExpiresAt(parseFuture("expiresAt", req.expiresAt(), now));
        if (req.allowedMethods() != null) {
            if (req.allowedMethods().isEmpty()) throw CommercialException.validation("allowedMethods", "must not be empty");
            for (MethodType m : req.allowedMethods()) {
                if (!MethodPolicy.isEnabled(ctx.merchant(), ctx.app(), ctx.environment(), m)) {
                    throw new CommercialException(ErrorCode.METHOD_NOT_ENABLED, m + " is not enabled for this merchant or app");
                }
            }
            l.setAllowedMethods(EnumSet.copyOf(req.allowedMethods()));
        }
        if (req.callbackUrl() != null) {
            checkCallbackUrl(ctx, req.callbackUrl());
            l.setCallbackUrl(blankToNull(req.callbackUrl()));
        }
        if (req.metadata() != null) l.setMetadata(RequestValidators.metadata(req.metadata()));
        if (touchesMoney) {
            if (req.currency() != null) l.setCurrency(currencyOrDefault(ctx, req.currency()));
            if (req.amount() != null) l.setAmount(scaled(req.amount()));
            if (req.minAmount() != null) l.setMinAmount(scaled(req.minAmount()));
            if (req.maxAmount() != null) l.setMaxAmount(scaled(req.maxAmount()));
            PaymentLinkRules.checkShape(l.getAmountType(), l.getAmount(), l.getMinAmount(), l.getMaxAmount());
        }
        l.setUpdatedAt(now);
        return view(links.save(l));
    }

    /**
     * Approves a payment attempt against a link, or throws. Returns the link so the payment
     * can take its reference, callback address and metadata.
     */
    public PaymentLink requireForAttempt(CommercialContext ctx, String linkId, MethodType method, BigDecimal amount, String currency) {
        PaymentLink l = requireOwn(ctx, linkId);
        PaymentLinkRules.checkAttempt(l, Instant.now(), method, scaled(amount), currency);
        if (l.getUsage() == PaymentLink.Usage.SINGLE && hasOtherAttempt(l.getId(), null)) {
            throw CommercialException.invalidState("This single use payment link already has a payment in progress or completed");
        }
        return l;
    }

    /**
     * For single use links: true when another attempt is open or already paid. Called again after the
     * new attempt is recorded, so two customers racing for the last use cannot both go through
     * (at worst both are turned away and one retries).
     */
    public boolean hasOtherAttempt(String linkId, String ownTransactionId) {
        Criteria c = Criteria.where("type").is(TxType.PAYMENT.name())
                .and("relations." + RELATION).is(linkId)
                .and("status").in(List.of(TxStatus.PENDING.name(), TxStatus.REQUIRES_ACTION.name(), TxStatus.SUCCESS.name()));
        if (ownTransactionId != null) c = c.and("id").ne(ownTransactionId);
        return mongo.count(Query.query(c), CommercialTransaction.class) > 0;
    }

    public static String relationKey() {
        return RELATION;
    }

    @Override
    public void onFinished(CommercialTransaction tx) {
        if (tx.getType() != TxType.PAYMENT || tx.getStatus() != TxStatus.SUCCESS) return;
        String linkId = tx.getRelations().get(RELATION);
        if (linkId == null) return;
        PaymentLink l = links.findById(linkId).orElse(null);
        if (l == null) return;
        // Recounting instead of adding one keeps this correct however many times it runs.
        long count = successCount(linkId);
        Document set = new Document("usageCount", count).append("updatedAt", Date.from(Instant.now()));
        mongo.updateFirst(Query.query(Criteria.where("id").is(linkId)), new BasicUpdate(new Document("$set", set)), PaymentLink.class);
        if (l.getUsage() == PaymentLink.Usage.SINGLE && count >= 1) {
            mongo.updateFirst(Query.query(Criteria.where("id").is(linkId).and("status").is(PaymentLink.Status.ACTIVE.name())),
                    new BasicUpdate(new Document("$set", new Document("status", PaymentLink.Status.COMPLETED.name()))), PaymentLink.class);
            log.info("Single use payment link {} is completed", linkId);
        }
    }

    // helpers

    private PaymentLink requireOwn(CommercialContext ctx, String id) {
        return links.findByIdAndMerchantIdAndLivemode(id, ctx.merchantId(), ctx.livemode())
                .orElseThrow(() -> CommercialException.notFound("Payment link"));
    }

    private long successCount(String linkId) {
        Criteria c = Criteria.where("type").is(TxType.PAYMENT.name()).and("relations." + RELATION).is(linkId)
                .and("status").is(TxStatus.SUCCESS.name());
        return mongo.count(Query.query(c), CommercialTransaction.class);
    }

    private static Criteria attempts(String linkId) {
        return Criteria.where("type").is(TxType.PAYMENT.name()).and("relations." + RELATION).is(linkId);
    }

    private static Set<MethodType> enabledMethods(CommercialContext ctx) {
        Set<MethodType> out = EnumSet.noneOf(MethodType.class);
        for (MethodType m : MethodType.values()) {
            if (MethodPolicy.isEnabled(ctx.merchant(), ctx.app(), ctx.environment(), m)) out.add(m);
        }
        return out;
    }

    private String currencyOrDefault(CommercialContext ctx, String requested) {
        String c = (requested != null ? requested : ctx.merchant().getDefaultCurrency()).toUpperCase(Locale.ROOT);
        if (!props.supportsCurrency(c)) throw CommercialException.validation("currency", c + " is not supported");
        return c;
    }

    private static BigDecimal scaled(BigDecimal v) {
        if (v == null) return null;
        if (v.scale() > 2 && v.stripTrailingZeros().scale() > 2) {
            throw CommercialException.validation("amount", "must have at most 2 decimal places");
        }
        return v.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static Instant parseFuture(String field, String value, Instant now) {
        if (value == null || value.isBlank()) return null;
        Instant t = instant(value, field);
        if (!t.isAfter(now)) throw CommercialException.validation(field, "must be in the future");
        return t;
    }

    private static Instant instant(String value, String field) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw CommercialException.validation(field, "must be an ISO 8601 UTC timestamp such as 2026-10-14T00:00:00Z");
        }
    }

    private static void checkCallbackUrl(CommercialContext ctx, String url) {
        if (url == null || url.isBlank()) return;
        CallbackUrlValidator.check(url, ctx.environment()).ifPresent(reason -> {
            throw new CommercialException(ErrorCode.INVALID_CALLBACK_URL, "callbackUrl: " + reason);
        });
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
