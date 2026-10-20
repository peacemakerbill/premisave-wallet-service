package com.premisave.wallet.commercial.config;

import com.premisave.wallet.commercial.config.properties.CommercialMpesaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the M-Pesa rail settings. Kept apart from CommercialWebConfig so the rail can be added without touching it. */
@Configuration
@EnableConfigurationProperties(CommercialMpesaProperties.class)
public class CommercialMpesaConfig {
}
