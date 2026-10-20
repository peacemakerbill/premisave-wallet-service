package com.premisave.wallet.commercial.rail.paypal;

import com.premisave.wallet.commercial.config.properties.CommercialRailsProperties;
import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.enums.DestinationType;
import com.premisave.wallet.commercial.enums.Direction;
import com.premisave.wallet.commercial.enums.Environment;
import com.premisave.wallet.commercial.enums.MethodType;
import com.premisave.wallet.commercial.enums.RailId;
import com.premisave.wallet.commercial.enums.ResultCode;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.enums.TxType;
import com.premisave.wallet.commercial.rail.CollectCommand;
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
 * Live PayPal payments and payouts. A payment is an Orders v2 order the customer approves on PayPal; once
 * approved, the rail captures it (the lookup in poll() does this), so no return page of ours is involved. A
 * saved PayPal account (vault) is charged without the customer. Refunds go back against the capture. Payouts
 * pay a PayPal email address through the Payouts API.
 *
 * PayPal results are found by looking them up (RailPoller); there is no webhook. Disputes raised in PayPal are
 * not brought in. Serves the live environment only; PayPal's sandbox credentials exercise the whole path.
 */
@Component("commercialPaypalRail")
@ConditionalOnProperty(prefix = "commercial.rails.paypal", name = "enabled", havingValue = "true")
public class PaypalRail implements PaymentRail {

    private static final Logger log = LoggerFactory.getLogger(PaypalRail.class);

    private final PaypalApi api;
    private final CommercialRailsProperties.Paypal props;

    public PaypalRail(PaypalApi api, CommercialRailsProperties props) {
        this.api = api;
        this.props = props.paypal();
    }

    @Override
    public RailId id() { return RailId.PAYPAL; }

    @Override
    public Set<Environment> environments() { return Set.of(Environment.LIVE); }

    @Override
    public List<RailCapability> capabilities() {
        return List.of(
                new RailCapability(MethodType.PAYPAL, Direction.COLLECTION, List.of("*"), props.currencies(),
                        props.minUsdCents(), props.maxUsdCents(), "INSTANT", "REDIRECT"),
                new RailCapability(MethodType.PAYPAL, Direction.PAYOUT, List.of("*"), props.currencies(),
                        props.minUsdCents(), props.maxUsdCents(), "INSTANT", "NONE"));
    }

    @Override
    public String providerName(MethodType method) { return "PAYPAL"; }

    @Override
    public boolean pollable() { return true; }

    // ------------------------------------------------------------------ collecting

    @Override
    public RailResponse collect(CollectCommand c) {
        if (c.method() != MethodType.PAYPAL) throw rejected(ResultCode.DECLINED, "The PayPal rail takes PayPal payments only");
        String currency = currencyOf(c.nativeCurrency());
        BigDecimal amount = c.nativeAmount() != null ? c.nativeAmount() : Money.fromCents(c.usdCents());

        Map<String, Object> unit = new LinkedHashMap<>();
        unit.put("reference_id", c.transactionId());
        unit.put("custom_id", c.transactionId());
        if (c.description() != null && !c.description().isBlank()) unit.put("description", truncate(c.description(), 120));
        Map<String, Object> money = new LinkedHashMap<>();
        money.put("currency_code", currency);
        money.put("value", PaypalMapper.value(amount, currency));
        unit.put("amount", money);

        Map<String, Object> paypal = new LinkedHashMap<>();
        String saved = c.methodOptions() == null ? null : asText(c.methodOptions().get("paymentMethodId"));
        if (saved != null) {
            String[] token = splitToken(saved);
            if (token == null) throw rejected(ResultCode.DECLINED, "The saved PayPal account cannot be used; the customer needs to add it again");
            paypal.put("vault_id", token[0]);
            paypal.put("customer_id", token[1]);
        }
        String returnUrl = option(c, "returnUrl");
        String cancelUrl = option(c, "cancelUrl");
        if (saved == null && (returnUrl == null || cancelUrl == null)) {
            throw rejected(ResultCode.DECLINED, "returnUrl and cancelUrl are needed to start a PayPal payment");
        }
        if (returnUrl != null && cancelUrl != null) {
            Map<String, Object> experience = new LinkedHashMap<>();
            experience.put("return_url", returnUrl);
            experience.put("cancel_url", cancelUrl);
            experience.put("user_action", "PAY_NOW");
            experience.put("shipping_preference", "NO_SHIPPING");
            paypal.put("experience_context", experience);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("intent", "CAPTURE");
        body.put("purchase_units", List.of(unit));
        if (!paypal.isEmpty()) body.put("payment_source", Map.of("paypal", paypal));

        PaypalApi.Result r;
        try {
            r = api.post("/v2/checkout/orders", body, "commercial-order-" + c.transactionId());
        } catch (ProviderHttp.NotSent e) {
            throw new RailException(RailException.Kind.UNAVAILABLE, ResultCode.PROVIDER_ERROR, e.getMessage());
        } catch (ProviderHttp.Unconfirmed e) {
            log.warn("PayPal payment {} is unconfirmed ({}); it will expire unless the customer finishes it", c.transactionId(), e.getMessage());
            return RailResponse.pending(null);
        }
        if (r.serverError() || r.status() == 429) {
            throw new RailException(RailException.Kind.UNAVAILABLE, ResultCode.PROVIDER_ERROR, "PayPal is not available right now");
        }
        if (!r.ok() || r.id().isBlank()) {
            throw rejected(PaypalMapper.errorCode(r.body()), PaypalMapper.errorMessage(r.body(), r.status()));
        }

        PaypalMapper.OrderState state = PaypalMapper.order(r.body());
        switch (state.kind()) {
            case COMPLETED:
                return new RailResponse(TxStatus.SUCCESS, null, r.id(), ResultCode.SUCCESS, state.description());
            case FAILED:
                return new RailResponse(TxStatus.FAILED, null, r.id(), state.code(), state.description());
            case APPROVED:
                return RailResponse.pending(r.id()); // captured by the next lookup
            default:
                String url = PaypalMapper.link(r.body(), "payer-action", "approve");
                if (url == null) {
                    // A saved account that needs no approval but is not complete yet.
                    return RailResponse.pending(r.id());
                }
                Map<String, Object> next = new LinkedHashMap<>();
                next.put("type", "REDIRECT");
                next.put("url", url);
                return RailResponse.requiresAction(next, r.id());
        }
    }

    /** A customer who has not approved cannot be charged, so withdrawing is just ceasing to capture. */
    @Override
    public boolean cancelCollection(CommercialTransaction tx) {
        return true;
    }

    // ------------------------------------------------------------------ results

    @Override
    public RailOutcome poll(CommercialTransaction tx) {
        String ref = tx.getProviderReference();
        if (ref == null || ref.isBlank()) return null;
        if (tx.getType() == TxType.PAYMENT) return pollPayment(tx, ref);
        if (tx.getType() == TxType.PAYOUT) {
            PaypalApi.Result r = api.get("/v1/payments/payouts/" + ref);
            return r.ok() ? PaypalMapper.payout(r.body(), ref) : null;
        }
        return null;
    }

    private RailOutcome pollPayment(CommercialTransaction tx, String orderId) {
        PaypalApi.Result r = api.get("/v2/checkout/orders/" + orderId);
        if (!r.ok()) return null;
        PaypalMapper.OrderState state = PaypalMapper.order(r.body());
        if (state.kind() == PaypalMapper.Kind.APPROVED) {
            PaypalApi.Result cap = api.post("/v2/checkout/orders/" + orderId + "/capture", null, "commercial-capture-" + tx.getId());
            if (!cap.ok()) {
                // Most often the customer's funding failed; look again to read the order's own verdict.
                PaypalApi.Result again = api.get("/v2/checkout/orders/" + orderId);
                state = again.ok() ? PaypalMapper.order(again.body()) : state;
                if (state.kind() == PaypalMapper.Kind.APPROVED) {
                    log.warn("PayPal capture for {} was refused: {}", tx.getId(), PaypalMapper.errorMessage(cap.body(), cap.status()));
                    if (cap.status() >= 400 && cap.status() < 500 && cap.status() != 429) {
                        return new RailOutcome(TxStatus.FAILED, PaypalMapper.errorCode(cap.body()),
                                PaypalMapper.errorMessage(cap.body(), cap.status()), PaypalMapper.firstIssue(cap.body()), null, orderId);
                    }
                    return null;
                }
            } else {
                state = PaypalMapper.order(cap.body());
            }
        }
        return PaypalMapper.outcome(state, orderId);
    }

    // ------------------------------------------------------------------ refunds

    @Override
    public RailRefundMode refundMode(CommercialTransaction payment) {
        return payment.getProviderReference() == null ? RailRefundMode.NONE : RailRefundMode.REFUND;
    }

    @Override
    public RailResponse refund(RefundCommand cmd) {
        String orderId = cmd.paymentProviderReference();
        PaypalApi.Result order;
        try {
            order = api.get("/v2/checkout/orders/" + orderId);
        } catch (ProviderHttp.NotSent | ProviderHttp.Unconfirmed e) {
            throw new RailException(RailException.Kind.UNAVAILABLE, ResultCode.PROVIDER_ERROR, e.getMessage());
        }
        String captureId = order.ok() ? PaypalMapper.text(PaypalMapper.firstCapture(order.body()), "id") : "";
        if (captureId.isBlank()) throw rejected(ResultCode.DECLINED, "PayPal has no completed capture to refund for this payment");

        String currency = currencyOf(cmd.nativeCurrency());
        BigDecimal amount = cmd.nativeAmount() != null ? cmd.nativeAmount() : Money.fromCents(cmd.usdCents());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", Map.of("currency_code", currency, "value", PaypalMapper.value(amount, currency)));
        body.put("invoice_id", "refund-" + cmd.transactionId());
        if (cmd.reason() != null && !cmd.reason().isBlank()) body.put("note_to_payer", truncate(cmd.reason(), 255));
        PaypalApi.Result r;
        try {
            r = api.post("/v2/payments/captures/" + captureId + "/refund", body, "commercial-refund-" + cmd.transactionId());
        } catch (ProviderHttp.NotSent e) {
            throw new RailException(RailException.Kind.UNAVAILABLE, ResultCode.PROVIDER_ERROR, e.getMessage());
        } catch (ProviderHttp.Unconfirmed e) {
            return RailResponse.pending(null);
        }
        if (r.ok()) {
            RailOutcome done = PaypalMapper.refund(r.body());
            if (done == null) return RailResponse.pending(r.id());
            return new RailResponse(done.status(), null, r.id(), done.code(), done.description());
        }
        if (r.serverError() || r.status() == 429) return RailResponse.pending(null);
        throw rejected(PaypalMapper.errorCode(r.body()), PaypalMapper.errorMessage(r.body(), r.status()));
    }

    // ------------------------------------------------------------------ payouts

    @Override
    public RailResponse payout(PayoutCommand c) {
        if (c.destinationType() != DestinationType.PAYPAL) {
            throw rejected(ResultCode.INVALID_DESTINATION, "The PayPal rail pays PayPal accounts only");
        }
        String email = asText(c.destination() == null ? null : c.destination().get("email"));
        if (email == null) throw rejected(ResultCode.INVALID_DESTINATION, "The PayPal email address is missing");
        String currency = currencyOf(c.nativeCurrency());
        BigDecimal amount = c.nativeAmount() != null ? c.nativeAmount() : Money.fromCents(c.usdCents());

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("sender_batch_id", c.transactionId());
        header.put("email_subject", "You have a payment");
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("recipient_type", "EMAIL");
        item.put("receiver", email);
        item.put("amount", Map.of("value", PaypalMapper.value(amount, currency), "currency", currency));
        item.put("sender_item_id", c.transactionId());
        if (c.remarks() != null && !c.remarks().isBlank()) item.put("note", truncate(c.remarks(), 4000));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sender_batch_header", header);
        body.put("items", List.of(item));

        PaypalApi.Result r;
        try {
            r = api.post("/v1/payments/payouts", body, "commercial-payout-" + c.transactionId());
        } catch (ProviderHttp.NotSent e) {
            throw new RailException(RailException.Kind.UNAVAILABLE, ResultCode.PROVIDER_ERROR, e.getMessage());
        } catch (ProviderHttp.Unconfirmed e) {
            // The payout may be on its way. Failing it would release the held money while the recipient could
            // still be paid, so it stays pending; the unique batch id makes a repeat safe.
            log.error("PayPal payout {} is unconfirmed ({}); keeping it pending", c.transactionId(), e.getMessage());
            return RailResponse.pending(null);
        }
        if (r.ok()) {
            String batch = PaypalMapper.text(PaypalMapper.map(r.body(), "batch_header"), "payout_batch_id");
            return RailResponse.pending(batch.isBlank() ? null : batch);
        }
        if (r.serverError() || r.status() == 429) {
            log.error("PayPal answered {} for payout {}; keeping it pending", r.status(), c.transactionId());
            return RailResponse.pending(null);
        }
        throw rejected(PaypalMapper.errorCode(r.body()), PaypalMapper.errorMessage(r.body(), r.status()));
    }

    // ------------------------------------------------------------------ helpers

    private String currencyOf(String nativeCurrency) {
        String c = nativeCurrency == null ? Money.BASE_CURRENCY : nativeCurrency.toUpperCase(Locale.ROOT);
        if (!props.currencies().contains(c)) throw rejected(ResultCode.DECLINED, "PayPal is not enabled for " + c);
        return c;
    }

    /** "vaultId|customerId", the form a saved PayPal account is stored in. */
    static String[] splitToken(String token) {
        String[] parts = token.split("\\|");
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank() || token.startsWith("setup:")) return null;
        return parts;
    }

    private static String option(CollectCommand c, String key) {
        Object v = c.methodOptions() == null ? null : c.methodOptions().get(key);
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v);
    }

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
