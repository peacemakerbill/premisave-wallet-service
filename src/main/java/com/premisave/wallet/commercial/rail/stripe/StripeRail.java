package com.premisave.wallet.commercial.rail.stripe;

import com.premisave.wallet.commercial.config.properties.CommercialRailsProperties;
import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.enums.Direction;
import com.premisave.wallet.commercial.enums.Environment;
import com.premisave.wallet.commercial.enums.MethodType;
import com.premisave.wallet.commercial.enums.RailId;
import com.premisave.wallet.commercial.enums.ResultCode;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.enums.TxType;
import com.premisave.wallet.commercial.rail.CollectCommand;
import com.premisave.wallet.commercial.rail.DisputeEvidenceCommand;
import com.premisave.wallet.commercial.rail.PaymentRail;
import com.premisave.wallet.commercial.rail.PayoutCommand;
import com.premisave.wallet.commercial.rail.RailCapability;
import com.premisave.wallet.commercial.rail.RailException;
import com.premisave.wallet.commercial.rail.RailOutcome;
import com.premisave.wallet.commercial.rail.RailRefundMode;
import com.premisave.wallet.commercial.rail.RailResponse;
import com.premisave.wallet.commercial.rail.RefundCommand;
import com.premisave.wallet.commercial.rail.http.ProviderHttp;
import com.premisave.wallet.commercial.util.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Live card payments through Stripe: a PaymentIntent the customer's browser confirms with the returned client
 * secret, or an off-session charge of a saved card for subscriptions. Refunds and dispute evidence go back to
 * Stripe. Results arrive on the signed webhook (StripeWebhookController) and are backed up by polling.
 *
 * A failed attempt is cancelled at Stripe at once, so a customer who retries the same intent later cannot be
 * charged for a payment already closed here. Payouts are not offered: Stripe pays merchants through their
 * own settlement, and bank payouts use the Flutterwave rail.
 *
 * Serves the live environment only. A Stripe test key (sk_test_) lets the whole path run with test cards.
 */
@Component("commercialStripeRail")
@ConditionalOnProperty(prefix = "commercial.rails.stripe", name = "enabled", havingValue = "true")
public class StripeRail implements PaymentRail {

    private static final Logger log = LoggerFactory.getLogger(StripeRail.class);

    private final StripeApi api;
    private final CommercialRailsProperties.Stripe props;

    public StripeRail(StripeApi api, CommercialRailsProperties props) {
        this.api = api;
        this.props = props.stripe();
        if (this.props.publishableKey() == null || this.props.publishableKey().isBlank()) {
            log.warn("commercial.rails.stripe.publishable-key is not set; new card payments cannot be started (saved cards still work)");
        }
    }

    @Override
    public RailId id() { return RailId.STRIPE; }

    @Override
    public Set<Environment> environments() { return Set.of(Environment.LIVE); }

    @Override
    public List<RailCapability> capabilities() {
        return List.of(new RailCapability(MethodType.CARD, Direction.COLLECTION, List.of("*"), props.currencies(),
                props.minUsdCents(), props.maxUsdCents(), "INSTANT", "CLIENT_SECRET"));
    }

    @Override
    public String providerName(MethodType method) { return "STRIPE"; }

    @Override
    public boolean pollable() { return true; }

    // ------------------------------------------------------------------ collecting

    @Override
    public RailResponse collect(CollectCommand c) {
        if (c.method() != MethodType.CARD) throw rejected(ResultCode.DECLINED, "The Stripe rail takes card payments only");
        String currency = c.nativeCurrency() != null ? c.nativeCurrency().toUpperCase(Locale.ROOT) : Money.BASE_CURRENCY;
        BigDecimal amount = c.nativeAmount() != null ? c.nativeAmount() : Money.fromCents(c.usdCents());
        long minor;
        try {
            minor = StripeMoney.minorUnits(amount, currency);
        } catch (IllegalArgumentException e) {
            throw rejected(ResultCode.DECLINED, e.getMessage());
        }

        String saved = c.methodOptions() == null ? null : asText(c.methodOptions().get("paymentMethodId"));
        String[] token = null;
        if (saved != null) {
            token = splitToken(saved);
            if (token == null) throw rejected(ResultCode.DECLINED, "The saved card cannot be used; the customer needs to add it again");
        } else if (props.publishableKey() == null || props.publishableKey().isBlank()) {
            throw new RailException(RailException.Kind.UNAVAILABLE, ResultCode.PROVIDER_ERROR,
                    "Card payments are not fully configured on this service");
        }

        Map<String, String> form = intentForm(c, minor, currency);
        if (token != null) {
            form.put("customer", token[0]);
            form.put("payment_method", token[1]);
            form.put("off_session", "true");
            form.put("confirm", "true");
        }

        StripeApi.Result r;
        try {
            r = api.post("/v1/payment_intents", form, "commercial-pay-" + c.transactionId());
        } catch (ProviderHttp.NotSent e) {
            throw new RailException(RailException.Kind.UNAVAILABLE, ResultCode.PROVIDER_ERROR, e.getMessage());
        } catch (ProviderHttp.Unconfirmed e) {
            // The intent may exist. The metadata lookup in poll() finds it, or the payment expires unpaid.
            log.warn("Stripe payment {} is unconfirmed ({}); it will be looked up", c.transactionId(), e.getMessage());
            return RailResponse.pending(null);
        }

        if (r.ok()) return fromIntent(r.body(), token != null);
        if (r.status() == 402 && token != null && "authentication_required".equals(r.errorCode())) {
            return needsCustomer(c, form, token);
        }
        if (r.status() == 402) {
            throw rejected(StripeMapper.declineCode(r.errorCode(), r.declineCode()), r.message());
        }
        if (r.status() == 401 || r.status() == 403 || r.status() == 429) {
            log.error("Stripe refused the request for payment {}: HTTP {} {}", c.transactionId(), r.status(), r.message());
            throw new RailException(RailException.Kind.UNAVAILABLE, ResultCode.PROVIDER_ERROR, "Stripe is not available right now");
        }
        if (r.serverError()) {
            log.warn("Stripe answered {} for payment {}; it will be looked up", r.status(), c.transactionId());
            return RailResponse.pending(null);
        }
        throw rejected(ResultCode.DECLINED, r.message());
    }

    /** The saved card needs the customer to authenticate: hand them a client secret to confirm with. */
    private RailResponse needsCustomer(CollectCommand c, Map<String, String> form, String[] token) {
        Map<String, String> retry = new LinkedHashMap<>(form);
        retry.remove("off_session");
        retry.remove("confirm");
        StripeApi.Result r;
        try {
            r = api.post("/v1/payment_intents", retry, "commercial-pay-" + c.transactionId() + "-auth");
        } catch (ProviderHttp.NotSent e) {
            throw new RailException(RailException.Kind.UNAVAILABLE, ResultCode.PROVIDER_ERROR, e.getMessage());
        } catch (ProviderHttp.Unconfirmed e) {
            return RailResponse.pending(null);
        }
        if (!r.ok()) throw rejected(ResultCode.DECLINED, r.message());
        return fromIntent(r.body(), false);
    }

    private RailResponse fromIntent(Map<String, Object> pi, boolean confirmedOffSession) {
        String id = StripeMapper.text(pi, "id");
        StripeMapper.IntentState state = StripeMapper.intent(pi);
        switch (state.verdict()) {
            case SUCCESS:
                return new RailResponse(TxStatus.SUCCESS, null, id, ResultCode.SUCCESS, state.description());
            case FAILED:
                return new RailResponse(TxStatus.FAILED, null, id, state.code(), state.description());
            case CANCELLED:
                return new RailResponse(TxStatus.CANCELLED, null, id, state.code(), state.description());
            default:
                String status = StripeMapper.text(pi, "status");
                if (status.equals("processing") || (confirmedOffSession && !status.equals("requires_action"))) {
                    return RailResponse.pending(id);
                }
                return RailResponse.requiresAction(clientSecret(pi), id);
        }
    }

    private Map<String, Object> clientSecret(Map<String, Object> pi) {
        Map<String, Object> next = new LinkedHashMap<>();
        next.put("type", "CLIENT_SECRET");
        next.put("clientSecret", StripeMapper.text(pi, "client_secret"));
        next.put("publishableKey", props.publishableKey());
        return next;
    }

    private static Map<String, String> intentForm(CollectCommand c, long minor, String currency) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("amount", String.valueOf(minor));
        form.put("currency", currency.toLowerCase(Locale.ROOT));
        form.put("payment_method_types[]", "card");
        if (c.description() != null && !c.description().isBlank()) form.put("description", truncate(c.description(), 500));
        Object email = c.customer() == null ? null : c.customer().get("email");
        if (email != null) form.put("receipt_email", String.valueOf(email));
        form.put("metadata[commercial_tx]", c.transactionId());
        form.put("metadata[merchant_id]", c.merchantId());
        return form;
    }

    /** "cus_x|pm_y", the form a saved card is stored in. */
    static String[] splitToken(String token) {
        String[] parts = token.split("\\|");
        if (parts.length != 2 || !parts[0].startsWith("cus_") || !parts[1].startsWith("pm_")) return null;
        return parts;
    }

    // ------------------------------------------------------------------ recalling, refunding, evidence

    @Override
    public boolean cancelCollection(CommercialTransaction tx) {
        String ref = tx.getProviderReference();
        if (ref == null || ref.isBlank()) return false;
        try {
            StripeApi.Result r = api.post("/v1/payment_intents/" + ref + "/cancel", new LinkedHashMap<>(), "commercial-cancel-" + tx.getId());
            return r.ok() && "canceled".equals(StripeMapper.text(r.body(), "status"));
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public RailRefundMode refundMode(CommercialTransaction payment) {
        return payment.getProviderReference() == null ? RailRefundMode.NONE : RailRefundMode.REFUND;
    }

    @Override
    public RailResponse refund(RefundCommand cmd) {
        String currency = cmd.nativeCurrency() != null ? cmd.nativeCurrency().toUpperCase(Locale.ROOT) : Money.BASE_CURRENCY;
        BigDecimal amount = cmd.nativeAmount() != null ? cmd.nativeAmount() : Money.fromCents(cmd.usdCents());
        Map<String, String> form = new LinkedHashMap<>();
        form.put("payment_intent", cmd.paymentProviderReference());
        try {
            form.put("amount", String.valueOf(StripeMoney.minorUnits(amount, currency)));
        } catch (IllegalArgumentException e) {
            throw rejected(ResultCode.DECLINED, e.getMessage());
        }
        form.put("metadata[commercial_tx]", cmd.transactionId());
        if (cmd.reason() != null && !cmd.reason().isBlank()) form.put("metadata[reason]", truncate(cmd.reason(), 400));
        StripeApi.Result r;
        try {
            r = api.post("/v1/refunds", form, "commercial-refund-" + cmd.transactionId());
        } catch (ProviderHttp.NotSent e) {
            throw new RailException(RailException.Kind.UNAVAILABLE, ResultCode.PROVIDER_ERROR, e.getMessage());
        } catch (ProviderHttp.Unconfirmed e) {
            // The refund may have been made; the same idempotency key makes a later look safe.
            return RailResponse.pending(null);
        }
        if (r.ok()) {
            RailOutcome done = StripeMapper.refund(r.body());
            if (done == null) return RailResponse.pending(r.id());
            return new RailResponse(done.status(), null, r.id(), done.code(), done.description());
        }
        if (r.serverError() || r.status() == 429) return RailResponse.pending(null);
        throw rejected(ResultCode.DECLINED, r.message());
    }

    @Override
    public void submitDisputeEvidence(DisputeEvidenceCommand cmd) {
        Map<String, String> form = new LinkedHashMap<>(StripeMapper.evidenceFields(cmd.evidence()));
        form.put("submit", "true");
        StripeApi.Result r;
        try {
            r = api.post("/v1/disputes/" + cmd.providerDisputeId(), form, "commercial-evidence-" + cmd.disputeId() + "-" + System.nanoTime());
        } catch (ProviderHttp.NotSent | ProviderHttp.Unconfirmed e) {
            throw new RailException(RailException.Kind.UNAVAILABLE, ResultCode.PROVIDER_ERROR, e.getMessage());
        }
        if (r.ok()) return;
        if (r.status() >= 500 || r.status() == 429) {
            throw new RailException(RailException.Kind.UNAVAILABLE, ResultCode.PROVIDER_ERROR, "Stripe is not available right now");
        }
        throw rejected(ResultCode.DECLINED, r.message());
    }

    @Override
    public RailResponse payout(PayoutCommand command) {
        throw new RailException(RailException.Kind.REJECTED, ResultCode.INVALID_DESTINATION, "The Stripe rail does not send payouts");
    }

    // ------------------------------------------------------------------ results

    @Override
    public RailOutcome poll(CommercialTransaction tx) {
        if (tx.getType() == TxType.PAYMENT) {
            Map<String, Object> pi = intentOf(tx);
            return pi == null ? null : resolveIntent(pi);
        }
        if (tx.getType() == TxType.REFUND && tx.getProviderReference() != null) {
            StripeApi.Result r = api.get("/v1/refunds/" + tx.getProviderReference(), null);
            return r.ok() ? StripeMapper.refund(r.body()) : null;
        }
        return null;
    }

    private Map<String, Object> intentOf(CommercialTransaction tx) {
        String ref = tx.getProviderReference();
        if (ref != null && !ref.isBlank()) {
            StripeApi.Result r = api.get("/v1/payment_intents/" + ref, null);
            return r.ok() ? r.body() : null;
        }
        Map<String, String> query = new LinkedHashMap<>();
        query.put("query", "metadata['commercial_tx']:'" + tx.getId() + "'");
        StripeApi.Result r = api.get("/v1/payment_intents/search", query);
        if (!r.ok()) return null;
        Object data = r.body().get("data");
        if (data instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first) {
            @SuppressWarnings("unchecked")
            Map<String, Object> pi = (Map<String, Object>) first;
            return pi;
        }
        return null;
    }

    /**
     * The final outcome a PaymentIntent has reached, or null while it is still open. A failed intent is cancelled
     * first so it cannot succeed later; if the cancel shows it actually succeeded, success wins.
     */
    public RailOutcome resolveIntent(Map<String, Object> pi) {
        StripeMapper.IntentState state = StripeMapper.intent(pi);
        String id = StripeMapper.text(pi, "id");
        switch (state.verdict()) {
            case WAITING:
                return null;
            case SUCCESS:
            case CANCELLED:
                return StripeMapper.outcome(state, id);
            default:
                try {
                    StripeApi.Result cancelled = api.post("/v1/payment_intents/" + id + "/cancel", new LinkedHashMap<>(),
                            "commercial-close-" + id);
                    if (!cancelled.ok()) {
                        StripeApi.Result now = api.get("/v1/payment_intents/" + id, null);
                        if (now.ok() && StripeMapper.intent(now.body()).verdict() == StripeMapper.Verdict.SUCCESS) {
                            return StripeMapper.outcome(StripeMapper.intent(now.body()), id);
                        }
                    }
                    return StripeMapper.outcome(state, id);
                } catch (RuntimeException e) {
                    log.warn("Could not close failed Stripe intent {}: {}", id, e.getMessage());
                    return null;
                }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static RailException rejected(ResultCode code, String message) {
        return new RailException(RailException.Kind.REJECTED, code, message);
    }

    private static String asText(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
