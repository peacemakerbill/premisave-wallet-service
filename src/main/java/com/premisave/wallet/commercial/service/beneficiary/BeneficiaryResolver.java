package com.premisave.wallet.commercial.service.beneficiary;

import com.premisave.wallet.commercial.enums.DestinationType;
import com.premisave.wallet.commercial.enums.Environment;
import com.premisave.wallet.commercial.rail.RailException;

import java.util.Map;

/**
 * Looks up who owns a destination. One implementation per provider lookup (Premisave wallets today;
 * bank account resolve, PayPal and others as their rails arrive). A destination type nobody supports
 * is reported to the merchant as match UNAVAILABLE, never as a pass.
 */
public interface BeneficiaryResolver {

    /**
     * found false means the provider says no such account exists. matchName is the full name used
     * for comparing; displayName is what the merchant sees (it may be masked).
     */
    record Resolution(boolean found, String matchName, String displayName, String source) {
        public static Resolution notFound(String source) {
            return new Resolution(false, null, null, source);
        }
    }

    boolean supports(Environment env, DestinationType type);

    /** Throws RailException (UNAVAILABLE) when the provider could not be asked. destination is already validated. */
    Resolution resolve(Environment env, DestinationType type, Map<String, Object> destination) throws RailException;
}
