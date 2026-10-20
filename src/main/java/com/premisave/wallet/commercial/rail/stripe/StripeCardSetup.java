package com.premisave.wallet.commercial.rail.stripe;

import com.premisave.wallet.commercial.config.properties.CommercialRailsProperties;
import com.premisave.wallet.commercial.entity.billing.BillingCustomer;
import com.premisave.wallet.commercial.entity.billing.CustomerPaymentMethod;
import com.premisave.wallet.commercial.enums.Environment;
import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.enums.MethodType;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.rail.http.ProviderHttp;
import com.premisave.wallet.commercial.security.CommercialContext;
import com.premisave.wallet.commercial.service.billing.CredentialSetup;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Saves a customer's card for repeated billing with a Stripe SetupIntent. The customer confirms the returned
 * client secret in their browser; the setup then completes by webhook (setup_intent.succeeded) or, if that is
 * missed, by the pending method poller. The stored token is "customer|paymentMethod", which StripeRail uses for
 * the off-session charge of each billing cycle.
 */
@Component("commercialStripeCardSetup")
@ConditionalOnProperty(prefix = "commercial.rails.stripe", name = "enabled", havingValue = "true")
public class StripeCardSetup implements CredentialSetup {

    private static final String PENDING_PREFIX = "seti_";

    private final StripeApi api;
    private final CommercialRailsProperties.Stripe props;

    public StripeCardSetup(StripeApi api, CommercialRailsProperties props) {
        this.api = api;
        this.props = props.stripe();
    }

    @Override
    public boolean supports(Environment environment, MethodType type) {
        return environment == Environment.LIVE && type == MethodType.CARD;
    }

    @Override
    public Result begin(CommercialContext ctx, BillingCustomer customer, CustomerPaymentMethod method) {
        if (props.publishableKey() == null || props.publishableKey().isBlank()) {
            throw new CommercialException(ErrorCode.METHOD_UNAVAILABLE, "Saving cards is not fully configured on this service");
        }
        try {
            Map<String, String> cus = new LinkedHashMap<>();
            cus.put("email", customer.getEmail());
            if (customer.getName() != null) cus.put("name", customer.getName());
            cus.put("metadata[commercial_pm]", method.getPaymentMethodId());
            cus.put("metadata[commercial_customer]", customer.getCustomerId());
            StripeApi.Result c = api.post("/v1/customers", cus, "commercial-cus-" + method.getPaymentMethodId());
            if (!c.ok() || c.id().isBlank()) throw unavailable(c);

            Map<String, String> seti = new LinkedHashMap<>();
            seti.put("customer", c.id());
            seti.put("payment_method_types[]", "card");
            seti.put("usage", "off_session");
            seti.put("metadata[commercial_pm]", method.getPaymentMethodId());
            StripeApi.Result s = api.post("/v1/setup_intents", seti, "commercial-seti-" + method.getPaymentMethodId());
            if (!s.ok() || s.id().isBlank()) throw unavailable(s);

            Map<String, Object> next = new LinkedHashMap<>();
            next.put("type", "CLIENT_SECRET");
            next.put("clientSecret", StripeMapper.text(s.body(), "client_secret"));
            next.put("publishableKey", props.publishableKey());
            return new Result(CustomerPaymentMethod.Status.PENDING_AUTHORISATION, "STRIPE", "Card (awaiting setup)", next,
                    s.id() + "|" + c.id());
        } catch (ProviderHttp.NotSent | ProviderHttp.Unconfirmed e) {
            throw new CommercialException(ErrorCode.PROVIDER_UNAVAILABLE, "The card provider is unavailable; nothing was saved");
        }
    }

    @Override
    public Progress poll(CustomerPaymentMethod method) {
        String token = method.getProviderToken();
        if (token == null || !token.startsWith(PENDING_PREFIX)) return Progress.pending();
        Instant created = method.getCreatedAt();
        if (created != null && Duration.between(created, Instant.now()).toHours() >= props.setupTimeoutHours()) {
            return Progress.failed("The card was not set up in time");
        }
        String setupId = token.substring(0, token.indexOf('|') < 0 ? token.length() : token.indexOf('|'));
        StripeApi.Result r = api.get("/v1/setup_intents/" + setupId, null);
        return r.ok() ? progressOf(r.body()) : Progress.pending();
    }

    /** What a SetupIntent says: still waiting, done (with the saved card), or failed. Also used by the webhook. */
    public Progress progressOf(Map<String, Object> setupIntent) {
        String status = StripeMapper.text(setupIntent, "status");
        switch (status) {
            case "succeeded": {
                String customer = StripeMapper.text(setupIntent, "customer");
                String pm = StripeMapper.text(setupIntent, "payment_method");
                if (customer.isBlank() || pm.isBlank()) return Progress.failed("Stripe did not return the saved card");
                return Progress.done(cardLabel(pm), customer + "|" + pm);
            }
            case "canceled":
                return Progress.failed("The card setup was cancelled");
            case "requires_payment_method": {
                Map<String, Object> error = StripeMapper.map(setupIntent, "last_setup_error");
                if (!error.isEmpty()) {
                    String m = StripeMapper.text(error, "message");
                    return Progress.failed(m.isBlank() ? "The card could not be saved" : m);
                }
                return Progress.pending();
            }
            default:
                return Progress.pending();
        }
    }

    private String cardLabel(String paymentMethodId) {
        try {
            StripeApi.Result r = api.get("/v1/payment_methods/" + paymentMethodId, null);
            if (r.ok()) {
                Map<String, Object> card = StripeMapper.map(r.body(), "card");
                return label(StripeMapper.text(card, "brand"), StripeMapper.text(card, "last4"));
            }
        } catch (RuntimeException e) {
            // The card is saved either way; a plain label is good enough.
        }
        return "Card";
    }

    static String label(String brand, String last4) {
        if (last4 == null || last4.isBlank()) return "Card";
        String b = brand == null || brand.isBlank() ? "Card" : brand.substring(0, 1).toUpperCase(Locale.ROOT) + brand.substring(1);
        return b + " ending " + last4;
    }

    private static CommercialException unavailable(StripeApi.Result r) {
        return new CommercialException(ErrorCode.PROVIDER_UNAVAILABLE, "The card provider refused the request: " + r.message());
    }
}
