package com.premisave.wallet.commercial.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Beneficiary checks. cacheHours is how long a successful lookup of a destination is reused
 * before the provider is asked again (the endpoint reference promises 24 hours).
 */
@ConfigurationProperties(prefix = "commercial.beneficiary")
public record CommercialBeneficiaryProperties(Integer cacheHours) {

    public CommercialBeneficiaryProperties {
        if (cacheHours == null || cacheHours < 1 || cacheHours > 168) cacheHours = 24;
    }
}
