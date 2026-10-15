package com.premisave.wallet.commercial.rail.premisave;

import com.premisave.wallet.commercial.billing.BillingCustomer;
import com.premisave.wallet.commercial.billing.CredentialSetup;
import com.premisave.wallet.commercial.billing.CustomerPaymentMethod;
import com.premisave.wallet.commercial.enums.Environment;
import com.premisave.wallet.commercial.enums.MethodType;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.security.CommercialContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Saving a Premisave wallet for subscriptions. The wallet owner has to say yes in the Premisave app to
 * letting this merchant charge their wallet, up to a set amount per charge. The approval is the token the
 * method is charged with, and the owner can withdraw it at any time.
 *
 * Needs no other bean that depends on the payment method service, because that service depends on this one.
 * The controller activates the method when the owner approves; the pending method poller does it for any
 * approval that missed that.
 */
@Component("commercialWalletMandateSetup")
@ConditionalOnProperty(prefix = "commercial.rails.premisave", name = "enabled", havingValue = "true")
public class WalletMandateSetup implements CredentialSetup {

    private final WalletConsentService consents;
    private final PremisaveWalletDebit wallets;

    public WalletMandateSetup(WalletConsentService consents, PremisaveWalletDebit wallets) {
        this.consents = consents;
        this.wallets = wallets;
    }

    @Override
    public boolean supports(Environment environment, MethodType type) {
        return environment == Environment.LIVE && type == MethodType.PREMISAVE_WALLET;
    }

    @Override
    public Result begin(CommercialContext ctx, BillingCustomer customer, CustomerPaymentMethod method) {
        String account = method.getDetails() == null ? null : method.getDetails().get("accountRef");
        var wallet = wallets.find(account);
        if (wallet.isEmpty() || wallet.get().isFrozen()) {
            throw CommercialException.validation("accountRef", "no active Premisave wallet exists for that account");
        }
        WalletConsent mandate = consents.openMandate(method.getPaymentMethodId(), ctx.merchantId(), account);
        Map<String, Object> next = new LinkedHashMap<>();
        next.put("type", "CUSTOMER_CONSENT");
        next.put("consentId", mandate.getId());
        next.put("instructions", "Open the Premisave app and approve recurring payments under Wallet, Payment requests.");
        next.put("expiresAt", mandate.getExpiresAt().toString());
        return new Result(CustomerPaymentMethod.Status.PENDING_AUTHORISATION, "PREMISAVE",
                "Premisave wallet (awaiting consent)", next, null);
    }

    @Override
    public Progress poll(CustomerPaymentMethod method) {
        WalletConsent mandate = consents.forMethod(method.getPaymentMethodId()).orElse(null);
        if (mandate == null) return Progress.pending();
        if (ConsentRules.lapsed(mandate, Instant.now())) {
            consents.expire(mandate);
            return Progress.failed("The wallet owner did not approve in time");
        }
        return switch (mandate.getStatus()) {
            case APPROVED -> Progress.done("Premisave wallet", mandate.getId());
            case DECLINED -> Progress.failed("The wallet owner declined");
            case EXPIRED, CANCELLED, REVOKED -> Progress.failed("The wallet owner did not approve");
            default -> Progress.pending();
        };
    }
}
