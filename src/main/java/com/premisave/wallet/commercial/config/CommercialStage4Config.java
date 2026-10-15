package com.premisave.wallet.commercial.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the settings of subscription billing. */
@Configuration
@EnableConfigurationProperties(CommercialBillingProperties.class)
public class CommercialStage4Config {
}
