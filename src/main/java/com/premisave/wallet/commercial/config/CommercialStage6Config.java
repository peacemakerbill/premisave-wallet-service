package com.premisave.wallet.commercial.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the settings of paybill payments by account reference. */
@Configuration
@EnableConfigurationProperties(CommercialC2bProperties.class)
public class CommercialStage6Config {
}
