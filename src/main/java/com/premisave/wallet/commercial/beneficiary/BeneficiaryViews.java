package com.premisave.wallet.commercial.beneficiary;

import com.premisave.wallet.commercial.dto.Views;

import java.util.LinkedHashMap;
import java.util.Map;

/** The shapes of sections 2.9, 7.1 and 7.2 of the endpoint reference. */
public final class BeneficiaryViews {

    private BeneficiaryViews() {}

    public static Map<String, Object> check(BeneficiaryCheck c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("checkId", c.getCheckId());
        m.put("destinationType", c.getDestinationType());
        m.put("destination", Views.maskedDestination(c.getDestination()));
        m.put("direction", c.getDirection());
        m.put("verified", c.isVerified());
        m.put("registeredName", c.getRegisteredName());
        m.put("expectedName", c.getExpectedName());
        m.put("match", c.getMatch());
        m.put("source", c.getSource());
        m.put("cached", c.isCached());
        m.put("checkedAt", Views.iso(c.getCreatedAt()));
        m.put("expiresAt", Views.iso(c.getExpiresAt()));
        return m;
    }

    public static Map<String, Object> lookup(BeneficiaryCheck c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("checkId", c.getCheckId());
        m.put("found", c.isVerified());
        m.put("registeredName", c.getRegisteredName());
        m.put("source", c.getSource());
        m.put("cached", c.isCached());
        m.put("expiresAt", Views.iso(c.getExpiresAt()));
        return m;
    }

    /** The short form carried in the error of a refused payout and in the payout's own beneficiary field. */
    public static Map<String, Object> summary(BeneficiaryCheck c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("checkId", c.getCheckId());
        m.put("registeredName", c.getRegisteredName());
        m.put("expectedName", c.getExpectedName());
        m.put("match", c.getMatch());
        return m;
    }
}
