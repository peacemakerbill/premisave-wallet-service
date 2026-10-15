package com.premisave.wallet.commercial.billing;

import com.premisave.wallet.commercial.enums.Direction;
import com.premisave.wallet.commercial.enums.Environment;
import com.premisave.wallet.commercial.enums.MethodType;
import com.premisave.wallet.commercial.rail.PaymentRail;
import com.premisave.wallet.commercial.security.CommercialContext;
import com.premisave.wallet.commercial.service.RailRegistry;
import com.premisave.wallet.commercial.util.Masking;
import org.springframework.stereotype.Component;

/**
 * Mobile money has no stored credential. The customer's number is kept and every billing cycle sends a
 * new prompt to it, so the method is usable at once. It works in both environments, on whichever rail
 * serves mobile money.
 */
@Component("commercialPerCyclePromptSetup")
public class PerCyclePromptSetup implements CredentialSetup {

    private final RailRegistry rails;

    public PerCyclePromptSetup(RailRegistry rails) {
        this.rails = rails;
    }

    @Override
    public boolean supports(Environment environment, MethodType type) {
        return type == MethodType.MOBILE_MONEY;
    }

    @Override
    public Result begin(CommercialContext ctx, BillingCustomer customer, CustomerPaymentMethod method) {
        PaymentRail rail = rails.select(ctx.environment(), Direction.COLLECTION, MethodType.MOBILE_MONEY, null, null, null);
        String provider = rail.providerName(MethodType.MOBILE_MONEY);
        String label = "Mobile money " + Masking.phone(method.getDetails().get("phoneNumber"));
        return new Result(CustomerPaymentMethod.Status.ACTIVE, provider, label, null, null);
    }
}
