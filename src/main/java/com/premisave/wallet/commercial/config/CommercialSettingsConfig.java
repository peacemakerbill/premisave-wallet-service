package com.premisave.wallet.commercial.config;

import com.premisave.wallet.commercial.onboarding.DocumentStorage;
import com.premisave.wallet.commercial.onboarding.LocalDiskStorage;
import com.premisave.wallet.commercial.onboarding.UnconfiguredDocumentStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.nio.file.Path;

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
    DocumentStorage commercialDocumentStorage(CommercialOnboardingProperties props) {
        if (!props.storage().configured()) {
            log.info("Onboarding document storage has no folder (commercial.onboarding.storage.path is empty); uploads will be refused");
            return new UnconfiguredDocumentStorage();
        }
        LocalDiskStorage disk = new LocalDiskStorage(Path.of(props.storage().path()));
        if (disk.available()) {
            log.info("Onboarding documents are stored in {}", disk.root());
        } else {
            log.warn("Onboarding document folder {} does not exist or is not writable; uploads will be refused until it is "
                    + "created or the network share is mounted", disk.root());
        }
        return disk;
    }
}