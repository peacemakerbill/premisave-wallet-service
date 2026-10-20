package com.premisave.wallet.commercial.service.link;

import com.premisave.wallet.commercial.enums.Environment;
import com.premisave.wallet.commercial.enums.MethodType;
import org.springframework.stereotype.Component;

/** Sandbox references point at Safaricom's public test paybill. Nothing is ever paid into them. */
@Component("commercialSandboxPayTo")
public class SandboxPayTo implements PayToProvider {

    @Override
    public boolean supports(Environment env, MethodType method) {
        return env == Environment.SANDBOX && method == MethodType.MOBILE_MONEY;
    }

    @Override
    public PayTo payTo(Environment env, MethodType method) {
        return new PayTo("MPESA", "174379");
    }
}
