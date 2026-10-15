package com.premisave.wallet.commercial.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the settings of the live provider rails. */
@Configuration
@EnableConfigurationProperties(CommercialRailsProperties.class)
public class CommercialStage5Config {
}
