package com.premisave.wallet.commercial.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the settings of refunds and disputes. */
@Configuration
@EnableConfigurationProperties({CommercialRefundProperties.class, CommercialDisputeProperties.class})
public class CommercialStage3Config {
}
