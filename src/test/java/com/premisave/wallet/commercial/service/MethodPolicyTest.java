package com.premisave.wallet.commercial.service;

import com.premisave.wallet.commercial.entity.MerchantProfile;
import com.premisave.wallet.commercial.enums.Environment;
import com.premisave.wallet.commercial.enums.AppStatus;
import com.premisave.wallet.commercial.enums.MethodType;
import com.premisave.wallet.commercial.security.AppContext;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MethodPolicyTest {

    private static MerchantProfile merchant(MethodType... methods) {
        MerchantProfile p = new MerchantProfile();
        p.setEnabledMethods(methods.length == 0 ? EnumSet.noneOf(MethodType.class) : EnumSet.of(methods[0], methods));
        return p;
    }

    private static AppContext app(MethodType... methods) {
        return new AppContext("app_1", "mer_1", Environment.LIVE, AppStatus.ACTIVE,
                methods.length == 0 ? null : EnumSet.of(methods[0], methods), null, 1L);
    }

    @Test
    void sandboxAllowsEveryMethodRegardlessOfMerchantApproval() {
        assertTrue(MethodPolicy.isEnabled(merchant(), app(), Environment.SANDBOX, MethodType.CARD));
    }

    @Test
    void liveFollowsMerchantApproval() {
        assertTrue(MethodPolicy.isEnabled(merchant(MethodType.MOBILE_MONEY), app(), Environment.LIVE, MethodType.MOBILE_MONEY));
        assertFalse(MethodPolicy.isEnabled(merchant(MethodType.MOBILE_MONEY), app(), Environment.LIVE, MethodType.CARD));
    }

    @Test
    void anAppCanOnlyNarrow() {
        MerchantProfile m = merchant(MethodType.MOBILE_MONEY, MethodType.CARD);
        assertTrue(MethodPolicy.isEnabled(m, app(MethodType.CARD), Environment.LIVE, MethodType.CARD));
        assertFalse(MethodPolicy.isEnabled(m, app(MethodType.CARD), Environment.LIVE, MethodType.MOBILE_MONEY));
        assertFalse(MethodPolicy.isEnabled(m, app(MethodType.PAYPAL), Environment.LIVE, MethodType.PAYPAL));
        assertFalse(MethodPolicy.isEnabled(m, app(MethodType.CARD), Environment.SANDBOX, MethodType.MOBILE_MONEY));
    }
}
