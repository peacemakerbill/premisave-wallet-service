package com.premisave.wallet.commercial.service.billing;

import com.premisave.wallet.commercial.dto.billing.AddPaymentMethodRequest;
import com.premisave.wallet.commercial.dto.billing.BillingViews;
import com.premisave.wallet.commercial.entity.billing.BillingCustomer;
import com.premisave.wallet.commercial.entity.billing.CustomerPaymentMethod;
import com.premisave.wallet.commercial.entity.billing.Plan;
import com.premisave.wallet.commercial.enums.Environment;
import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.enums.MethodType;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.repository.billing.CustomerPaymentMethodRepository;
import com.premisave.wallet.commercial.security.CommercialContext;
import com.premisave.wallet.commercial.service.CommercialIdempotencyService;
import com.premisave.wallet.commercial.service.MethodPolicy;
import com.premisave.wallet.commercial.util.CallbackUrlValidator;
import com.premisave.wallet.commercial.util.Ids;
import com.premisave.wallet.commercial.util.RequestValidators;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.BasicUpdate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Saved payment methods (section 8.8). Only the provider's token is ever stored. Starting the setup is
 * delegated to the CredentialSetup that serves the method; finishing it happens when the customer
 * completes the provider step (the sandbox authorise helper stands in for that today).
 */
@Service("commercialPaymentMethodService")
public class PaymentMethodService {

    private static final Logger log = LoggerFactory.getLogger(PaymentMethodService.class);

    private final CommercialIdempotencyService idempotency;
    private final CustomerService customers;
    private final CustomerPaymentMethodRepository methods;
    private final List<CredentialSetup> setups;
    private final List<PaymentMethodListener> listeners;
    private final BillingEvents events;
    private final MongoTemplate mongo;

    public PaymentMethodService(CommercialIdempotencyService idempotency, CustomerService customers,
                                CustomerPaymentMethodRepository methods, List<CredentialSetup> setups,
                                List<PaymentMethodListener> listeners, BillingEvents events, MongoTemplate mongo) {
        this.idempotency = idempotency;
        this.customers = customers;
        this.methods = methods;
        this.setups = setups;
        this.listeners = listeners;
        this.events = events;
        this.mongo = mongo;
    }

    public CommercialIdempotencyService.Outcome<Map<String, Object>> add(CommercialContext ctx, String customerId,
                                                                AddPaymentMethodRequest req, String idempotencyKey, String bodyHash) {
        // The customer is looked up first so an unknown customer is a 404 even on a replayed key.
        customers.require(ctx, customerId);
        return idempotency.execute(ctx, idempotencyKey, bodyHash, "PAYMENT_METHOD",
                () -> doAdd(ctx, customerId, req),
                id -> BillingViews.paymentMethod(methods.findById(id).orElseThrow(() -> CommercialException.notFound("Payment method"))));
    }

    private String doAdd(CommercialContext ctx, String customerId, AddPaymentMethodRequest req) {
        BillingCustomer customer = customers.require(ctx, customerId);
        MethodType type = req.type();
        if (!Plan.RECURRING_METHODS.contains(type)) {
            throw CommercialException.validation("type", type + " cannot be billed repeatedly; use CARD, PAYPAL, PREMISAVE_WALLET or MOBILE_MONEY");
        }
        if (!MethodPolicy.isEnabled(ctx.merchant(), ctx.app(), ctx.environment(), type)) {
            throw new CommercialException(ErrorCode.METHOD_NOT_ENABLED, type + " is not enabled for this merchant or app");
        }

        if ((type == MethodType.CARD || type == MethodType.PAYPAL) && (customer.getEmail() == null || customer.getEmail().isBlank())) {
            throw CommercialException.validation("customer.email", "the customer needs an email address before a " + type + " method can be saved");
        }

        CustomerPaymentMethod pm = new CustomerPaymentMethod();
        pm.setPaymentMethodId(Ids.next("pm"));
        pm.setCustomerId(customerId);
        pm.setMerchantId(ctx.merchantId());
        pm.setAppId(ctx.appId());
        pm.setLivemode(ctx.livemode());
        pm.setType(type);
        pm.setReturnUrl(checkReturnUrl(ctx, req.returnUrl(), type));
        switch (type) {
            case MOBILE_MONEY -> {
                String phone = req.phoneNumber() != null && !req.phoneNumber().isBlank() ? req.phoneNumber() : customer.getPhone();
                if (phone == null) throw CommercialException.validation("phoneNumber", "is required for MOBILE_MONEY");
                pm.getDetails().put("phoneNumber", RequestValidators.normalisePhone("phoneNumber", phone));
            }
            case PREMISAVE_WALLET -> {
                if (req.accountRef() == null || req.accountRef().isBlank()) {
                    throw CommercialException.validation("accountRef", "is required for PREMISAVE_WALLET");
                }
                pm.getDetails().put("accountRef", req.accountRef().trim());
            }
            default -> { }
        }

        CredentialSetup setup = setups.stream().filter(s -> s.supports(ctx.environment(), type)).findFirst()
                .orElseThrow(() -> new CommercialException(ErrorCode.METHOD_UNAVAILABLE,
                        type + " cannot be saved for recurring billing in this environment yet"));
        CredentialSetup.Result r = setup.begin(ctx, customer, pm);
        pm.setStatus(r.status());
        pm.setProvider(r.provider());
        pm.setLabel(r.label());
        pm.setNextAction(r.nextAction());
        pm.setProviderToken(r.providerToken());

        boolean first = methods.findByCustomerId(customerId).stream()
                .noneMatch(m -> m.getStatus() != CustomerPaymentMethod.Status.REMOVED && m.getStatus() != CustomerPaymentMethod.Status.FAILED);
        boolean makeDefault = req.makeDefault() == null ? first : req.makeDefault();
        Instant now = Instant.now();
        pm.setCreatedAt(now);
        pm.setUpdatedAt(now);
        pm.setDefaultMethod(makeDefault);
        methods.insert(pm);
        if (makeDefault) clearOtherDefaults(customerId, pm.getPaymentMethodId());
        return pm.getPaymentMethodId();
    }

    /** The customer finished the provider step. Safe to call again: only a pending method changes. */
    public CustomerPaymentMethod activate(String paymentMethodId, String label, String providerToken) {
        Document set = new Document("status", CustomerPaymentMethod.Status.ACTIVE.name())
                .append("nextAction", null).append("updatedAt", Date.from(Instant.now()));
        if (label != null) set.append("label", label);
        if (providerToken != null) set.append("providerToken", providerToken);
        long moved = mongo.updateFirst(guard(paymentMethodId), new BasicUpdate(new Document("$set", set)),
                CustomerPaymentMethod.class).getModifiedCount();
        CustomerPaymentMethod pm = methods.findById(paymentMethodId).orElseThrow(() -> CommercialException.notFound("Payment method"));
        if (moved > 0 || pm.getStatus() == CustomerPaymentMethod.Status.ACTIVE) {
            if (moved > 0) events.paymentMethodActivated(pm);
            notifyListeners(pm, true);
        }
        return pm;
    }

    /** The customer abandoned or failed the provider step. Safe to call again. */
    public CustomerPaymentMethod fail(String paymentMethodId, String reason) {
        Document set = new Document("status", CustomerPaymentMethod.Status.FAILED.name())
                .append("nextAction", null).append("failureReason", reason).append("updatedAt", Date.from(Instant.now()));
        mongo.updateFirst(guard(paymentMethodId), new BasicUpdate(new Document("$set", set)), CustomerPaymentMethod.class);
        CustomerPaymentMethod pm = methods.findById(paymentMethodId).orElseThrow(() -> CommercialException.notFound("Payment method"));
        if (pm.getStatus() == CustomerPaymentMethod.Status.FAILED) notifyListeners(pm, false);
        return pm;
    }

    /** Sandbox helper: does what the customer would do at the provider. */
    public Map<String, Object> authoriseSandbox(CommercialContext ctx, String customerId, String paymentMethodId, String outcome) {
        if (ctx.environment() != Environment.SANDBOX) throw CommercialException.notFound("Resource");
        customers.require(ctx, customerId);
        CustomerPaymentMethod pm = require(ctx, customerId, paymentMethodId);
        String o = outcome == null || outcome.isBlank() ? "SUCCESS" : outcome.trim().toUpperCase(Locale.ROOT);
        if (!o.equals("SUCCESS") && !o.equals("FAILED")) throw CommercialException.validation("outcome", "must be SUCCESS or FAILED");
        if (pm.getStatus() != CustomerPaymentMethod.Status.PENDING_AUTHORISATION) {
            throw CommercialException.invalidState("This payment method is " + pm.getStatus() + ", not waiting for the customer");
        }
        CustomerPaymentMethod after;
        if (o.equals("SUCCESS")) {
            SandboxCredentialSetup.Completed done = SandboxCredentialSetup.completed(pm.getType(), pm.getPaymentMethodId());
            after = activate(pm.getPaymentMethodId(), done.label(), done.token());
        } else {
            after = fail(pm.getPaymentMethodId(), "The customer did not complete the authorisation");
        }
        return BillingViews.paymentMethod(after);
    }

    public CustomerPaymentMethod require(CommercialContext ctx, String customerId, String paymentMethodId) {
        CustomerPaymentMethod pm = methods.findByPaymentMethodIdAndMerchantIdAndLivemode(paymentMethodId, ctx.merchantId(), ctx.livemode())
                .orElseThrow(() -> CommercialException.notFound("Payment method"));
        if (!pm.getCustomerId().equals(customerId)) throw CommercialException.notFound("Payment method");
        return pm;
    }

    private static Query guard(String paymentMethodId) {
        return Query.query(Criteria.where("paymentMethodId").is(paymentMethodId)
                .and("status").is(CustomerPaymentMethod.Status.PENDING_AUTHORISATION.name()));
    }

    private void clearOtherDefaults(String customerId, String keep) {
        mongo.updateMulti(Query.query(Criteria.where("customerId").is(customerId).and("defaultMethod").is(true)
                        .and("paymentMethodId").ne(keep)),
                new BasicUpdate(new Document("$set", new Document("defaultMethod", false))), CustomerPaymentMethod.class);
    }

    private void notifyListeners(CustomerPaymentMethod pm, boolean activated) {
        for (PaymentMethodListener l : listeners) {
            try {
                if (activated) l.onActivated(pm); else l.onFailed(pm);
            } catch (RuntimeException e) {
                log.error("Payment method listener failed for {}", pm.getPaymentMethodId(), e);
            }
        }
    }

    private static String checkReturnUrl(CommercialContext ctx, String url, MethodType type) {
        if (url == null || url.isBlank()) {
            if (type == MethodType.PAYPAL) throw CommercialException.validation("returnUrl", "is required for PAYPAL");
            return null;
        }
        CallbackUrlValidator.check(url.trim(), ctx.environment()).ifPresent(reason -> {
            throw CommercialException.validation("returnUrl", reason);
        });
        return url.trim();
    }
}
