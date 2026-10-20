package com.premisave.wallet.commercial.service.beneficiary;

import com.premisave.wallet.commercial.enums.DestinationType;
import com.premisave.wallet.commercial.enums.Environment;
import com.premisave.wallet.commercial.enums.ResultCode;
import com.premisave.wallet.commercial.rail.RailException;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Sandbox lookups never contact a provider. Every account is registered to JOHN DOE, so the
 * merchant can try EXACT, PARTIAL and MISMATCH by changing expectedName. The last digits of the
 * account number choose special cases: ending 0001 means no such account, ending 0002 means the
 * provider is down.
 */
@Component("commercialSandboxBeneficiaryResolver")
public class SandboxBeneficiaryResolver implements BeneficiaryResolver {

    static final String SOURCE = "SANDBOX_SIMULATED";

    @Override
    public boolean supports(Environment env, DestinationType type) {
        return env == Environment.SANDBOX && type != DestinationType.CRYPTO;
    }

    @Override
    public Resolution resolve(Environment env, DestinationType type, Map<String, Object> destination) {
        String id = identifier(type, destination);
        if (id.endsWith("0002")) {
            throw new RailException(RailException.Kind.UNAVAILABLE, ResultCode.PROVIDER_ERROR, "The simulated provider is unavailable");
        }
        if (id.endsWith("0001")) return Resolution.notFound(SOURCE);
        String name = type == DestinationType.BUSINESS_ACCOUNT ? "ACME TRADERS LTD" : "JOHN DOE";
        return new Resolution(true, name, name, SOURCE);
    }

    static String identifier(DestinationType type, Map<String, Object> d) {
        String key = switch (type) {
            case MOBILE_MONEY -> "phoneNumber";
            case BUSINESS_ACCOUNT -> "shortcode";
            case BANK_ACCOUNT -> "accountNumber";
            case PAYPAL -> "email";
            case PREMISAVE_WALLET -> "accountRef";
            case CRYPTO -> "address";
        };
        Object v = d.get(key);
        return v == null ? "" : String.valueOf(v);
    }
}
