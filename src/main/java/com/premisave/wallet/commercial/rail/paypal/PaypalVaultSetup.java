package com.premisave.wallet.commercial.rail.paypal;

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
import java.util.Map;

/**
 * Saves a customer's PayPal account for repeated billing with the PayPal vault. The customer approves a setup
 * token on PayPal; once they have, it is exchanged for a payment token, which is the stored credential. There
 * is no webhook for this step, so the pending method poller tries the exchange until it succeeds or the
 * setup times out. The stored token is "vaultId|paypalCustomerId", which PaypalRail uses to charge each cycle.
 */
@Component("commercialPaypalVaultSetup")
@ConditionalOnProperty(prefix = "commercial.rails.paypal", name = "enabled", havingValue = "true")
public class PaypalVaultSetup implements CredentialSetup {

    private static final String SETUP_PREFIX = "setup:";

    private final PaypalApi api;
    private final CommercialRailsProperties.Paypal props;

    public PaypalVaultSetup(PaypalApi api, CommercialRailsProperties props) {
        this.api = api;
        this.props = props.paypal();
    }

    @Override
    public boolean supports(Environment environment, MethodType type) {
        return environment == Environment.LIVE && type == MethodType.PAYPAL;
    }

    @Override
    public Result begin(CommercialContext ctx, BillingCustomer customer, CustomerPaymentMethod method) {
        String returnUrl = method.getReturnUrl();
        if (returnUrl == null || returnUrl.isBlank()) {
            throw CommercialException.validation("returnUrl", "is required to save a PayPal account");
        }
        String paypalCustomer = api.paypalCustomerId(customer.getCustomerId());
        Map<String, Object> experience = new LinkedHashMap<>();
        experience.put("return_url", returnUrl);
        experience.put("cancel_url", returnUrl);
        Map<String, Object> paypal = new LinkedHashMap<>();
        paypal.put("usage_type", "MERCHANT");
        paypal.put("customer_type", "CONSUMER");
        paypal.put("experience_context", experience);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("payment_source", Map.of("paypal", paypal));
        body.put("customer", Map.of("id", paypalCustomer));
        try {
            PaypalApi.Result r = api.post("/v3/vault/setup-tokens", body, "commercial-setup-" + method.getPaymentMethodId());
            String approve = PaypalMapper.link(r.body(), "approve");
            if (!r.ok() || r.id().isBlank() || approve == null) {
                throw new CommercialException(ErrorCode.PROVIDER_UNAVAILABLE,
                        "PayPal refused the request: " + PaypalMapper.errorMessage(r.body(), r.status()));
            }
            Map<String, Object> next = new LinkedHashMap<>();
            next.put("type", "REDIRECT");
            next.put("url", approve);
            return new Result(CustomerPaymentMethod.Status.PENDING_AUTHORISATION, "PAYPAL", "PayPal (awaiting approval)", next,
                    SETUP_PREFIX + r.id() + "|" + paypalCustomer);
        } catch (ProviderHttp.NotSent | ProviderHttp.Unconfirmed e) {
            throw new CommercialException(ErrorCode.PROVIDER_UNAVAILABLE, "PayPal is unavailable; nothing was saved");
        }
    }

    @Override
    public Progress poll(CustomerPaymentMethod method) {
        String token = method.getProviderToken();
        if (token == null || !token.startsWith(SETUP_PREFIX)) return Progress.pending();
        Instant created = method.getCreatedAt();
        if (created != null && Duration.between(created, Instant.now()).toHours() >= props.setupTimeoutHours()) {
            return Progress.failed("The PayPal account was not approved in time");
        }
        int bar = token.indexOf('|');
        if (bar < 0) return Progress.failed("The PayPal setup record is damaged");
        String setupId = token.substring(SETUP_PREFIX.length(), bar);
        String customerId = token.substring(bar + 1);

        Map<String, Object> body = Map.of("payment_source", Map.of("token", Map.of("id", setupId, "type", "SETUP_TOKEN")));
        PaypalApi.Result r = api.post("/v3/vault/payment-tokens", body, "commercial-vault-" + method.getPaymentMethodId());
        if (!r.ok()) return Progress.pending(); // not approved yet (or a passing fault); the timeout ends it
        String vaultId = r.id();
        if (vaultId.isBlank()) return Progress.failed("PayPal did not return the saved account");
        String email = PaypalMapper.text(PaypalMapper.map(PaypalMapper.map(r.body(), "payment_source"), "paypal"), "email_address");
        return Progress.done(email.isBlank() ? "PayPal account" : "PayPal " + email, vaultId + "|" + customerId);
    }
}
