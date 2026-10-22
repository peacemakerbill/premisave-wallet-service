package com.premisave.wallet.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings for the escrow endpoints (/internal/escrow/**), see EscrowService.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "escrow")
public class EscrowProperties {

    /**
     * Callers whose initiatedBy may use the escrow endpoints. The API key is shared by every internal service, so
     * this is what makes the endpoints "just for the listing service". Compared ignoring case. Anything else gets
     * 403. An empty list switches the check off.
     */
    private List<String> allowedInitiators = new ArrayList<>(List.of("LISTING_SERVICE"));

    /**
     * Minutes after which a hold or payout that is still PENDING is settled by the recovery sweep: completed if
     * its money movement already happened, rolled back to FAILED if it did not.
     */
    private int stalePendingMinutes = 5;

    /** How often the recovery sweep runs (milliseconds). Read by the @Scheduled annotation, kept here for reference. */
    private long recoveryIntervalMs = 60_000;
}
