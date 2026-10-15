package com.premisave.wallet.commercial.billing;

import com.premisave.wallet.commercial.config.CommercialProperties;
import com.premisave.wallet.commercial.enums.Environment;
import com.premisave.wallet.commercial.enums.MethodType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SandboxCredentialSetupTest {

    private final SandboxCredentialSetup setup =
            new SandboxCredentialSetup(new CommercialProperties(null, null, null, null, null, null, null, null, null));

    private static CustomerPaymentMethod pm(MethodType type) {
        CustomerPaymentMethod pm = new CustomerPaymentMethod();
        pm.setPaymentMethodId("pm_1");
        pm.setType(type);
        return pm;
    }

    @Test
    void servesTheSandboxOnlyAndNotMobileMoney() {
        assertTrue(setup.supports(Environment.SANDBOX, MethodType.CARD));
        assertTrue(setup.supports(Environment.SANDBOX, MethodType.PAYPAL));
        assertTrue(setup.supports(Environment.SANDBOX, MethodType.PREMISAVE_WALLET));
        assertFalse(setup.supports(Environment.SANDBOX, MethodType.MOBILE_MONEY));
        assertFalse(setup.supports(Environment.LIVE, MethodType.CARD));
    }

    @Test
    void aCardStartsPendingWithAClientSecret() {
        CredentialSetup.Result r = setup.begin(null, null, pm(MethodType.CARD));
        assertEquals(CustomerPaymentMethod.Status.PENDING_AUTHORISATION, r.status());
        assertEquals("CLIENT_SECRET", r.nextAction().get("type"));
        assertEquals("STRIPE", r.provider());
        assertNull(r.providerToken());
    }

    @Test
    void payPalStartsPendingWithARedirect() {
        CredentialSetup.Result r = setup.begin(null, null, pm(MethodType.PAYPAL));
        assertEquals("REDIRECT", r.nextAction().get("type"));
        assertTrue(String.valueOf(r.nextAction().get("url")).contains("pm_1"));
    }

    @Test
    void aWalletStartsPendingWithCustomerConsent() {
        CredentialSetup.Result r = setup.begin(null, null, pm(MethodType.PREMISAVE_WALLET));
        assertEquals("CUSTOMER_CONSENT", r.nextAction().get("type"));
        assertEquals("PREMISAVE", r.provider());
    }

    @Test
    void completionGivesALabelAndAToken() {
        SandboxCredentialSetup.Completed c = SandboxCredentialSetup.completed(MethodType.CARD, "pm_1");
        assertEquals("Visa ending 4242", c.label());
        assertEquals("tok_sandbox_pm_1", c.token());
        assertEquals("PayPal account", SandboxCredentialSetup.completed(MethodType.PAYPAL, "pm_2").label());
    }
}
