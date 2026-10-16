package com.premisave.wallet.commercial.security;

import com.premisave.wallet.commercial.entity.Limits;
import com.premisave.wallet.commercial.enums.AppStatus;
import com.premisave.wallet.commercial.enums.Environment;
import com.premisave.wallet.commercial.enums.MethodType;

import java.util.Set;

/**
 * What the developer service says about the app making a call: its status and the optional narrowing of the merchant's
 * methods and limits. The developer service owns apps; this service only applies what it is told.
 *
 * @param enabledMethods null means "same as the merchant"
 * @param limits         null means "no app limits"
 * @param version        the app's change counter, so the newest copy wins
 */
public record AppContext(String appId, String merchantId, Environment environment, AppStatus status,
                         Set<MethodType> enabledMethods, Limits limits, long version) {

    public boolean active() {
        return status == AppStatus.ACTIVE;
    }
}
