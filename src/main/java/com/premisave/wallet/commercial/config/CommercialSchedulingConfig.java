package com.premisave.wallet.commercial.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on the scheduled jobs of the commercial API. Enabling scheduling twice is harmless. */
@Configuration
@EnableScheduling
public class CommercialSchedulingConfig {
}
