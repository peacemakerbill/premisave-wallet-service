package com.premisave.wallet.commercial.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bulk payouts.
 *
 * itemsPerRun: how many queued payouts are sent to their rails on each dispatcher run.
 * With the default interval of two seconds and ten items this is about five payouts a second.
 * stuckAfterMinutes: an item claimed for sending that has had no answer for this long is
 * closed as timed out so its money is released and the batch can finish.
 */
@ConfigurationProperties(prefix = "commercial.batches")
public record CommercialBatchProperties(Integer itemsPerRun, Integer stuckAfterMinutes, Integer maxItems) {

    public CommercialBatchProperties {
        if (itemsPerRun == null || itemsPerRun < 1) itemsPerRun = 10;
        if (stuckAfterMinutes == null || stuckAfterMinutes < 5) stuckAfterMinutes = 60;
        if (maxItems == null || maxItems < 1 || maxItems > 500) maxItems = 500;
    }
}
