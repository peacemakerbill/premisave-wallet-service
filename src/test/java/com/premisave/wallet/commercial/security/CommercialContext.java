package com.premisave.wallet.commercial.security;

import com.premisave.wallet.commercial.entity.MerchantProfile;
import com.premisave.wallet.commercial.enums.Environment;

/** Who is calling, for which environment. Resolved once per request by CommercialRequestInterceptor. */
public record CommercialContext(Environment environment, MerchantProfile merchant, AppContext app, String requestId) {

    public String merchantId() { return merchant.getMerchantId(); }

    public String appId() { return app.appId(); }

    public boolean livemode() { return environment.livemode(); }

    public String walletId() { return merchant.walletIdFor(environment); }
}
