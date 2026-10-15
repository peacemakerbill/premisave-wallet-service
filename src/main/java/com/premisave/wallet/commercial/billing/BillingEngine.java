package com.premisave.wallet.commercial.billing;

import com.premisave.wallet.commercial.config.CommercialBillingProperties;
import com.premisave.wallet.commercial.dto.CreatePaymentRequest;
import com.premisave.wallet.commercial.dto.CustomerInfo;
import com.premisave.wallet.commercial.dto.MethodSpec;
import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.entity.MerchantApp;
import com.premisave.wallet.commercial.entity.MerchantProfile;
import com.premisave.wallet.commercial.enums.AppStatus;
import com.premisave.wallet.commercial.enums.Environment;
import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.enums.MerchantStatus;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.repository.MerchantAppRepository;
import com.premisave.wallet.commercial.repository.MerchantProfileRepository;
import com.premisave.wallet.commercial.security.CommercialContext;
import com.premisave.wallet.commercial.service.CommercialPaymentService;
import com.premisave.wallet.commercial.service.CommercialTransactionStore;
import com.premisave.wallet.commercial.util.Hashing;
import com.premisave.wallet.commercial.util.Ids;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.BasicUpdate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs billing cycles and collection attempts.
 *
 * A cycle is: create the invoice for the period (one per subscription and period, enforced by a unique
 * index), move the subscription on to the next period (a guarded update), then try to collect. Each step is
 * safe to repeat, so a crash anywhere leaves something the next run finishes: an invoice with no attempt
 * is picked up by the retry sweep, an attempt with no payment is discarded by the recovery sweep, and a
 * payment that exists is found again through the relations stored on it.
 *
 * A collection attempt creates an ordinary payment. Its Idempotency-Key is built from the invoice and
 * the attempt number and its body hash is fixed, so repeating the same attempt can never charge twice.
 */
@Component("commercialBillingEngine")
public class BillingEngine {

    private static final Logger log = LoggerFactory.getLogger(BillingEngine.class);
    private static final int MAX_DESCRIPTION = 200;

    private final BillingSubscriptionRepository subs;
    private final InvoiceStore invoices;
    private final InvoiceSettlement settlement;
    private final SubscriptionTransitions transitions;
    private final CommercialPaymentService payments;
    private final CommercialTransactionStore txStore;
    private final CustomerPaymentMethodRepository methods;
    private final BillingCustomerRepository customers;
    private final MerchantProfileRepository merchants;
    private final MerchantAppRepository apps;
    private final BillingEvents events;
    private final MongoTemplate mongo;
    private final CommercialBillingProperties props;

    public BillingEngine(BillingSubscriptionRepository subs, InvoiceStore invoices, InvoiceSettlement settlement,
                         SubscriptionTransitions transitions, CommercialPaymentService payments,
                         CommercialTransactionStore txStore, CustomerPaymentMethodRepository methods,
                         BillingCustomerRepository customers, MerchantProfileRepository merchants,
                         MerchantAppRepository apps, BillingEvents events, MongoTemplate mongo,
                         CommercialBillingProperties props) {
        this.subs = subs;
        this.invoices = invoices;
        this.settlement = settlement;
        this.transitions = transitions;
        this.payments = payments;
        this.txStore = txStore;
        this.methods = methods;
        this.customers = customers;
        this.merchants = merchants;
        this.apps = apps;
        this.events = events;
        this.mongo = mongo;
        this.props = props;
    }

    // Cycles

    /**
     * Bills the period boundary a subscription has reached. Returns true when something was done (a cycle
     * billed, or a subscription that was set to end was ended).
     */
    public boolean billDue(String subscriptionId, Instant now) {
        Subscription s = subs.findById(subscriptionId).orElse(null);
        if (s == null || !Subscription.BILLABLE.contains(s.getState())) return false;
        if (s.getNextBillingAt() == null || s.getNextBillingAt().isAfter(now)) return false;
        Optional<CommercialContext> ctx = contextFor(s);
        if (ctx.isEmpty()) return false;

        if (s.isCancelAtPeriodEnd()) {
            transitions.terminate(s.getId(), Subscription.State.CANCELLED,
                    s.getCancelReason() != null ? s.getCancelReason() : "Cancelled at the end of the period", now);
            return true;
        }

        SubscriptionRules.Cycle cycle = SubscriptionRules.cycleFor(s);
        InvoiceStore.Inserted ins = invoices.insertOrExisting(newInvoice(s, cycle, now));
        if (!advance(s, cycle)) {
            log.debug("Subscription {} changed while its cycle {} was being billed", s.getId(), cycle.label());
            return false;
        }
        attemptScheduled(ins.invoice().getId(), now);
        return true;
    }

    private Invoice newInvoice(Subscription s, SubscriptionRules.Cycle c, Instant now) {
        Invoice inv = new Invoice();
        inv.setId(Ids.next("inv"));
        inv.setSubscriptionId(s.getId());
        inv.setMerchantId(s.getMerchantId());
        inv.setLivemode(s.isLivemode());
        inv.setBillingPeriod(c.label());
        inv.setPeriodStart(c.start());
        inv.setPeriodEnd(c.end());
        inv.setDueAt(c.start());
        inv.setStatus(Invoice.Status.OPEN);
        inv.setAmount(c.amount());
        inv.setCurrency(c.terms().getCurrency());
        inv.setQuantity(s.getQuantity());
        inv.setRetryScheduleDays(new ArrayList<>(c.terms().getRetryScheduleDays()));
        inv.setOnExhausted(c.terms().getOnExhausted());
        // Due at once: if this run stops before the first attempt, the retry sweep makes it.
        inv.setNextRetryAt(c.start());
        inv.setCreatedAt(now);
        inv.setUpdatedAt(Instant.now());
        return inv;
    }

    /** Moves the subscription onto the cycle just invoiced. False when someone else changed it first. */
    private boolean advance(Subscription s, SubscriptionRules.Cycle c) {
        Document set = new Document("periodIndex", c.index())
                .append("periodStart", Date.from(c.start()))
                .append("periodEnd", Date.from(c.end()))
                .append("nextBillingAt", Date.from(c.end()))
                .append("anchor", Date.from(c.anchor()))
                .append("generation", c.generation())
                .append("state", Subscription.State.ACTIVE.name())
                .append("setupFeeBilled", s.isSetupFeeBilled() || c.includesSetupFee())
                .append("upcomingNotified", false);
        Document unset = new Document();
        Criteria guard = Criteria.where("version").is(s.getVersion());
        if (c.planSwitched()) {
            Document terms = new Document();
            mongo.getConverter().write(c.terms(), terms);
            terms.remove("_class");
            set.append("terms", terms).append("planId", c.planId());
            unset.append("pendingTerms", "").append("pendingPlanId", "");
            Document withKey = new Document(set).append("activeKey",
                    Subscription.activeKeyFor(s.getMerchantId(), s.isLivemode(), s.getCustomerId(), c.planId()));
            try {
                return transitions.apply(s.getId(), guard, withKey, unset).isPresent();
            } catch (DuplicateKeyException other) {
                log.warn("Subscription {} switched to plan {} but the customer already has another live subscription to it",
                        s.getId(), c.planId());
            }
        }
        return transitions.apply(s.getId(), guard, set, unset).isPresent();
    }

    // Attempts

    /** Collects an invoice that is due, if it is still waiting and its subscription can be billed. */
    public void attemptScheduled(String invoiceId, Instant now) {
        Invoice inv = invoices.find(invoiceId).orElse(null);
        if (inv == null || inv.getStatus() != Invoice.Status.OPEN || inv.getNextRetryAt() == null
                || inv.getNextRetryAt().isAfter(now) || InvoiceRules.inFlight(inv)) return;
        Subscription s = subs.findById(inv.getSubscriptionId()).orElse(null);
        if (s == null) return;
        if (s.getState().isTerminal()) {
            invoices.mutate(invoiceId, InvoiceRules::voidInvoice);
            return;
        }
        if (!Subscription.RETRYABLE.contains(s.getState())) return;
        Optional<CommercialContext> ctx = contextFor(s);
        if (ctx.isEmpty()) return;
        run(ctx.get(), s, inv, s.getPaymentMethodId(), now, false);
    }

    /** A merchant asking to collect an invoice now, optionally on a different payment method. */
    public Invoice retryManually(CommercialContext ctx, String invoiceId, String overrideMethodId) {
        Invoice inv = invoices.find(invoiceId)
                .filter(i -> i.getMerchantId().equals(ctx.merchantId()) && i.isLivemode() == ctx.livemode())
                .orElseThrow(() -> CommercialException.notFound("Invoice"));
        Subscription s = subs.findById(inv.getSubscriptionId()).orElseThrow(() -> CommercialException.notFound("Subscription"));
        String block = InvoiceRules.manualRetryBlock(inv, s.getState());
        if (block != null) throw CommercialException.invalidState(block);
        String pmId = overrideMethodId != null && !overrideMethodId.isBlank() ? overrideMethodId.trim() : s.getPaymentMethodId();
        CustomerPaymentMethod pm = methods.findByPaymentMethodIdAndMerchantIdAndLivemode(pmId, ctx.merchantId(), ctx.livemode())
                .filter(m -> m.getCustomerId().equals(s.getCustomerId()))
                .orElseThrow(() -> CommercialException.notFound("Payment method"));
        if (pm.getStatus() != CustomerPaymentMethod.Status.ACTIVE) {
            throw new CommercialException(ErrorCode.METHOD_UNAVAILABLE, "That payment method is " + pm.getStatus() + " and cannot be charged");
        }
        run(ctx, s, inv, pmId, Instant.now(), true);
        return invoices.find(invoiceId).orElseThrow(() -> CommercialException.notFound("Invoice"));
    }

    private void run(CommercialContext ctx, Subscription s, Invoice inv, String pmId, Instant now, boolean manual) {
        AtomicReference<Invoice.Attempt> began = new AtomicReference<>();
        Optional<Invoice> after = invoices.mutate(inv.getId(), i -> {
            began.set(null);
            if (i.getStatus() == Invoice.Status.PAID || i.getStatus() == Invoice.Status.VOID || InvoiceRules.inFlight(i)) return false;
            if (!manual && (i.getStatus() != Invoice.Status.OPEN || i.getNextRetryAt() == null || i.getNextRetryAt().isAfter(now))) return false;
            began.set(InvoiceRules.begin(i, pmId, now));
            return true;
        });
        if (after.isEmpty() || began.get() == null) {
            if (manual) throw CommercialException.invalidState("The invoice changed while it was being retried; check its status and try again");
            return;
        }
        charge(ctx, s, after.get(), began.get().getAttempt(), pmId, now, manual);
    }

    private void charge(CommercialContext ctx, Subscription s, Invoice inv, int attempt, String pmId, Instant now, boolean manual) {
        CustomerPaymentMethod pm = methods.findById(pmId).orElse(null);
        BillingCustomer customer = customers.findById(s.getCustomerId()).orElse(null);
        if (pm == null || customer == null || !pm.getCustomerId().equals(s.getCustomerId())
                || pm.getStatus() != CustomerPaymentMethod.Status.ACTIVE) {
            settlement.failed(inv.getId(), attempt, null, "PAYMENT_METHOD_INACTIVE", now);
            return;
        }
        CreatePaymentRequest request = paymentRequest(s, inv, customer, pm);
        String key = inv.getId() + "_a" + attempt;
        String hash = Hashing.sha256Hex((inv.getId() + "|" + attempt + "|" + inv.getAmount().toPlainString() + "|"
                + inv.getCurrency() + "|" + pmId).getBytes());
        Map<String, String> relations = new LinkedHashMap<>();
        relations.put(InvoiceSettlement.RELATION_INVOICE, inv.getId());
        relations.put(InvoiceSettlement.RELATION_ATTEMPT, String.valueOf(attempt));
        relations.put(InvoiceSettlement.RELATION_SUBSCRIPTION, s.getId());
        try {
            Map<String, Object> view = payments.createWithRelations(ctx, request, key, hash, relations).value();
            String paymentId = String.valueOf(view.get("id"));
            txStore.find(paymentId).ifPresent(tx -> record(inv.getId(), attempt, tx, now));
        } catch (CommercialException e) {
            if (e.code() == ErrorCode.REQUEST_IN_PROGRESS) {
                // An earlier try of this very attempt is still running; the recovery sweep settles it.
                if (manual) throw e;
                return;
            }
            String code = e.code() == ErrorCode.PROVIDER_UNAVAILABLE ? "PROVIDER_ERROR" : e.code().name();
            settlement.failed(inv.getId(), attempt, null, code, now);
            if (manual) throw e;
        }
    }

    /** Brings the invoice up to date with what the payment looks like now. Repeating it changes nothing. */
    private void record(String invoiceId, int attempt, CommercialTransaction tx, Instant now) {
        switch (tx.getStatus()) {
            case SUCCESS -> settlement.paid(invoiceId, attempt, tx.getId(), tx.getFeeCents(), now);
            case FAILED, TIMED_OUT, CANCELLED -> settlement.failed(invoiceId, attempt, tx.getId(), InvoiceSettlement.codeOf(tx), now);
            case REQUIRES_ACTION -> invoices.mutate(invoiceId, i -> InvoiceRules.attachPayment(i, attempt, tx.getId(), true));
            case PENDING -> invoices.mutate(invoiceId, i -> InvoiceRules.attachPayment(i, attempt, tx.getId(), false));
            default -> { }
        }
    }

    static CreatePaymentRequest paymentRequest(Subscription s, Invoice inv, BillingCustomer customer, CustomerPaymentMethod pm) {
        String phone = pm.getDetails().getOrDefault("phoneNumber", customer.getPhone());
        CustomerInfo who = new CustomerInfo(customer.getName(), customer.getEmail(), phone, pm.getDetails().get("accountRef"));
        String token = pm.getProviderToken() != null ? pm.getProviderToken() : pm.getPaymentMethodId();
        MethodSpec method = new MethodSpec(pm.getType(), pm.getProvider(), pm.getReturnUrl(), pm.getReturnUrl(), null, null, token);
        Map<String, String> metadata = new LinkedHashMap<>(s.getMetadata());
        metadata.put("invoiceId", inv.getId());
        metadata.put("subscriptionId", s.getId());
        metadata.put("customerId", s.getCustomerId());
        metadata.put("billingPeriod", inv.getBillingPeriod());
        String description = s.getTerms().getPlanName() + " " + inv.getBillingPeriod();
        if (description.length() > MAX_DESCRIPTION) description = description.substring(0, MAX_DESCRIPTION);
        return new CreatePaymentRequest(inv.getAmount(), inv.getCurrency(), inv.getId(), description, who, method, null,
                s.getCallbackUrl(), Boolean.TRUE, null, metadata);
    }

    // Recovery

    /** Finishes an invoice whose attempt never completed its bookkeeping (a crash, or a listener that failed). */
    public void reconcile(String invoiceId, Instant now) {
        Invoice inv = invoices.find(invoiceId).orElse(null);
        if (inv == null) return;
        for (Invoice.Attempt a : new ArrayList<>(inv.getAttempts())) {
            if (a.isFinal()) continue;
            CommercialTransaction tx = paymentOf(invoiceId, a.getAttempt());
            if (tx == null) {
                if (Invoice.Attempt.INITIATING.equals(a.getStatus())) {
                    int n = a.getAttempt();
                    invoices.mutate(invoiceId, i -> InvoiceRules.discard(i, n));
                    log.info("Attempt {} of invoice {} never produced a payment and was discarded", n, invoiceId);
                }
                continue;
            }
            record(invoiceId, a.getAttempt(), tx, now);
        }
    }

    private CommercialTransaction paymentOf(String invoiceId, int attempt) {
        return mongo.findOne(Query.query(Criteria.where("relations." + InvoiceSettlement.RELATION_INVOICE).is(invoiceId)
                .and("relations." + InvoiceSettlement.RELATION_ATTEMPT).is(String.valueOf(attempt))), CommercialTransaction.class);
    }

    // Notices

    /** Sends invoice.upcoming once for the current billing date. */
    public void notifyUpcoming(Subscription s) {
        if (s.isCancelAtPeriodEnd() || s.getNextBillingAt() == null) return;
        long marked = mongo.updateFirst(Query.query(Criteria.where("id").is(s.getId()).and("upcomingNotified").is(false)
                        .and("version").is(s.getVersion())),
                new BasicUpdate(new Document("$set", new Document("upcomingNotified", true))), Subscription.class).getModifiedCount();
        if (marked == 1) events.upcoming(s, SubscriptionRules.cycleFor(s));
    }

    // Context

    /** The caller context to bill as, or empty when the merchant or app is not active in that environment. */
    public Optional<CommercialContext> contextFor(Subscription s) {
        MerchantProfile merchant = merchants.findById(s.getMerchantId()).orElse(null);
        MerchantApp app = s.getAppId() == null ? null : apps.findById(s.getAppId()).orElse(null);
        if (merchant == null || app == null) return Optional.empty();
        Environment env = s.isLivemode() ? Environment.LIVE : Environment.SANDBOX;
        if (merchant.statusFor(env) != MerchantStatus.ACTIVE || app.getStatus() != AppStatus.ACTIVE) return Optional.empty();
        return Optional.of(new CommercialContext(env, merchant, app, Ids.requestId()));
    }

    public CommercialBillingProperties properties() {
        return props;
    }

    /** Subscriptions that are due, oldest first. */
    public List<Subscription> dueSubscriptions(Instant now, int limit) {
        Criteria c = Criteria.where("state").in(Subscription.BILLABLE.stream().map(Enum::name).toList())
                .and("nextBillingAt").lte(now);
        return mongo.find(Query.query(c).with(Sort.by(Sort.Direction.ASC, "nextBillingAt")).limit(limit), Subscription.class);
    }
}
