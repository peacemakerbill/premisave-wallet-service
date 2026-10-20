package com.premisave.wallet.commercial.service.billing;

import com.premisave.wallet.commercial.entity.billing.BillingCustomer;
import com.premisave.wallet.commercial.entity.billing.CustomerPaymentMethod;
import com.premisave.wallet.commercial.enums.Environment;
import com.premisave.wallet.commercial.enums.MethodType;
import com.premisave.wallet.commercial.security.CommercialContext;

import java.util.Map;

/**
 * The step that turns "I want to bill this customer repeatedly" into something that can be charged: a
 * card setup intent, a PayPal billing agreement, the customer's consent to a wallet debit, or nothing at
 * all for mobile money. The real providers each implement this when their rail is added; until then live
 * card, PayPal and wallet methods answer METHOD_UNAVAILABLE.
 */
public interface CredentialSetup {

    /** What starting the setup produced. */
    record Result(CustomerPaymentMethod.Status status, String provider, String label,
                  Map<String, Object> nextAction, String providerToken) {}

    /** Where a setup that was waiting for the customer stands now. */
    record Progress(Kind kind, String label, String token, String reason) {
        public enum Kind { PENDING, DONE, FAILED }

        public static Progress pending() { return new Progress(Kind.PENDING, null, null, null); }

        public static Progress done(String label, String token) { return new Progress(Kind.DONE, label, token, null); }

        public static Progress failed(String reason) { return new Progress(Kind.FAILED, null, null, reason); }
    }

    boolean supports(Environment environment, MethodType type);

    Result begin(CommercialContext ctx, BillingCustomer customer, CustomerPaymentMethod method);

    /**
     * Asks the provider whether the customer finished the step started by begin. The default says
     * "still waiting", which is right for a setup that is finished by the sandbox helper or by a webhook.
     */
    default Progress poll(CustomerPaymentMethod method) {
        return Progress.pending();
    }
}
