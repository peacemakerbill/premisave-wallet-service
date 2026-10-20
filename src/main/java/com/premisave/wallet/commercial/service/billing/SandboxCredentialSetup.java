package com.premisave.wallet.commercial.service.billing;

import com.premisave.wallet.commercial.config.properties.CommercialProperties;
import com.premisave.wallet.commercial.entity.billing.BillingCustomer;
import com.premisave.wallet.commercial.entity.billing.CustomerPaymentMethod;
import com.premisave.wallet.commercial.enums.Environment;
import com.premisave.wallet.commercial.enums.MethodType;
import com.premisave.wallet.commercial.security.CommercialContext;
import com.premisave.wallet.commercial.util.Ids;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sandbox card, PayPal and wallet methods. They start PENDING_AUTHORISATION with a believable next action
 * and are finished by the sandbox authorise helper, which stands in for the customer.
 */
@Component("commercialSandboxCredentialSetup")
public class SandboxCredentialSetup implements CredentialSetup {

    private final CommercialProperties props;

    public SandboxCredentialSetup(CommercialProperties props) {
        this.props = props;
    }

    @Override
    public boolean supports(Environment environment, MethodType type) {
        return environment == Environment.SANDBOX
                && (type == MethodType.CARD || type == MethodType.PAYPAL || type == MethodType.PREMISAVE_WALLET);
    }

    @Override
    public Result begin(CommercialContext ctx, BillingCustomer customer, CustomerPaymentMethod method) {
        Map<String, Object> next = new LinkedHashMap<>();
        String provider;
        String label;
        switch (method.getType()) {
            case CARD -> {
                provider = "STRIPE";
                label = "Card (awaiting setup)";
                next.put("type", "CLIENT_SECRET");
                next.put("clientSecret", "seti_sandbox_" + method.getPaymentMethodId() + "_secret_" + Ids.next("s"));
                next.put("publishableKey", "pk_sandbox");
            }
            case PAYPAL -> {
                provider = "PAYPAL";
                label = "PayPal (awaiting approval)";
                next.put("type", "REDIRECT");
                next.put("url", props.sandbox().redirectBase() + "/paypal/agreement/" + method.getPaymentMethodId());
            }
            default -> {
                provider = "PREMISAVE";
                label = "Premisave wallet (awaiting consent)";
                next.put("type", "CUSTOMER_CONSENT");
                next.put("instructions", "Sandbox: the wallet owner would approve recurring debits in the Premisave app.");
            }
        }
        return new Result(CustomerPaymentMethod.Status.PENDING_AUTHORISATION, provider, label, next, null);
    }

    /** The label and token a method gets once the customer has finished the step. */
    public static Completed completed(MethodType type, String paymentMethodId) {
        String label = switch (type) {
            case CARD -> "Visa ending 4242";
            case PAYPAL -> "PayPal account";
            default -> "Premisave wallet";
        };
        return new Completed(label, "tok_sandbox_" + paymentMethodId);
    }

    public record Completed(String label, String token) {}
}
