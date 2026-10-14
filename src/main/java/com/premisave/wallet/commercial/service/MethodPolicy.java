package com.premisave.wallet.commercial.service;

import com.premisave.wallet.commercial.entity.MerchantApp;
import com.premisave.wallet.commercial.entity.MerchantProfile;
import com.premisave.wallet.commercial.enums.Environment;
import com.premisave.wallet.commercial.enums.MethodType;

/**
 * Which methods a caller may use. Sandbox allows every method so developers
 * can build against all of them; live follows what was approved for the
 * merchant. An app can only narrow the result, never widen it.
 */
public final class MethodPolicy {

    private MethodPolicy() {}

    public static boolean isEnabled(MerchantProfile merchant, MerchantApp app, Environment env, MethodType method) {
        boolean merchantAllows = env == Environment.SANDBOX || merchant.getEnabledMethods().contains(method);
        boolean appAllows = app.getEnabledMethods() == null || app.getEnabledMethods().contains(method);
        return merchantAllows && appAllows;
    }
}
