package com.premisave.wallet.commercial.config;

import com.premisave.wallet.commercial.onboarding.DocumentStorage;
import com.premisave.wallet.commercial.onboarding.GoogleDriveStorage;
import com.premisave.wallet.commercial.onboarding.UnconfiguredDocumentStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Registers the settings classes of the commercial API (payment links, bulk payouts, beneficiary checks, refunds,
 * disputes, subscription billing, provider rails, paybill payments and onboarding) and builds the onboarding
 * document store. CommercialProperties and the M-Pesa settings are registered by CommercialWebConfig and
 * CommercialMpesaConfig.
 */
@Configuration
@EnableConfigurationProperties({
        CommercialLinksProperties.class,
        CommercialBatchProperties.class,
        CommercialBeneficiaryProperties.class,
        CommercialRefundProperties.class,
        CommercialDisputeProperties.class,
        CommercialBillingProperties.class,
        CommercialRailsProperties.class,
        CommercialC2bProperties.class,
        CommercialOnboardingProperties.class})
public class CommercialSettingsConfig {

    private static final Logger log = LoggerFactory.getLogger(CommercialSettingsConfig.class);

    @Bean("commercialDocumentStorage")
    DocumentStorage commercialDocumentStorage(CommercialOnboardingProperties props, JsonMapper json) {
        if (props.drive().configured()) {
            return new GoogleDriveStorage(props.drive(), json);
        }
        if (Boolean.TRUE.equals(props.drive().enabled())) {
            log.warn("Onboarding document storage is switched on but incomplete: set the service account key and "
                    + "commercial.onboarding.drive.folder-id; uploads will be refused until then");
        } else {
            log.info("Onboarding document storage is switched off (commercial.onboarding.drive.enabled=false); uploads will be refused");
        }
        return new UnconfiguredDocumentStorage();
    }
}