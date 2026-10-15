package com.premisave.wallet.commercial.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the settings of payment links, bulk payouts and beneficiary checks. */
@Configuration
@EnableConfigurationProperties({CommercialLinksProperties.class, CommercialBatchProperties.class,
        CommercialBeneficiaryProperties.class})
public class CommercialStage2Config {
}
