package com.premisave.wallet.commercial.config;

import com.premisave.wallet.commercial.onboarding.DocumentStorage;
import com.premisave.wallet.commercial.onboarding.LocalDiskStorage;
import com.premisave.wallet.commercial.onboarding.MountCheckedStorage;
import com.premisave.wallet.commercial.onboarding.NetworkMount;
import com.premisave.wallet.commercial.onboarding.UnconfiguredDocumentStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
        CommercialOnboardingProperties.Storage cfg = props.storage();
        if (!cfg.configured()) {
            log.info(cfg.networkMode()
                    ? "Onboarding document storage is in NETWORK mode but STORAGE_NETWORK_MOUNT_PATH is empty; uploads will be refused"
                    : "Onboarding document storage is in LOCAL mode but STORAGE_ROOT is empty; uploads will be refused");
            return new UnconfiguredDocumentStorage();
        }
        LocalDiskStorage disk = new LocalDiskStorage(cfg.folder());
        if (!cfg.networkMode()) {
            logFolder("LOCAL", disk);
            return disk;
        }
        CommercialOnboardingProperties.Network net = cfg.network();
        if (!net.requireMount()) {
            log.warn("STORAGE_NETWORK_REQUIRE_MOUNT=false: the mount is not checked, so if the share drops off the server "
                    + "documents can be written to the local disk");
            logFolder("NETWORK (mount not checked)", disk);
            return disk;
        }
        NetworkMount mount = new NetworkMount(java.nio.file.Path.of(net.mountPath()), net.protocol(), net.host(), net.share());
        String problem = mount.problem();
        if (problem != null) {
            log.warn("Onboarding document share is not usable: {}. Uploads will be refused until it is mounted", problem);
        } else {
            createDirectoryOnShare(disk);
            logFolder("NETWORK " + net.protocol() + " share", disk);
        }
        return new MountCheckedStorage(disk, mount);
    }

    /** Only called once the share is confirmed mounted, so the folder is created on the share and never on the local disk. */
    private void createDirectoryOnShare(LocalDiskStorage disk) {
        if (java.nio.file.Files.isDirectory(disk.root())) return;
        try {
            java.nio.file.Files.createDirectories(disk.root());
            log.info("Created the document folder {} on the share", disk.root());
        } catch (java.io.IOException | RuntimeException e) {
            log.warn("Could not create the document folder {} on the share: {}", disk.root(), e.getMessage());
        }
    }

    private void logFolder(String kind, LocalDiskStorage disk) {
        if (disk.available()) {
            log.info("Onboarding documents are stored in {} ({})", disk.root(), kind);
        } else {
            log.warn("Onboarding document folder {} ({}) does not exist or is not writable; uploads will be refused until it is "
                    + "created or the network share is mounted", disk.root(), kind);
        }
    }
}