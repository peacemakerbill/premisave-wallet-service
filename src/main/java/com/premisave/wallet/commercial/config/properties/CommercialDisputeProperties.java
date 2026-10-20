package com.premisave.wallet.commercial.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Dispute settings. evidenceWindowDays is how long a merchant has to answer a new test dispute. */
@ConfigurationProperties(prefix = "commercial.disputes")
public record CommercialDisputeProperties(Integer evidenceWindowDays) {

    public CommercialDisputeProperties {
        if (evidenceWindowDays == null || evidenceWindowDays < 1) evidenceWindowDays = 14;
    }
}
