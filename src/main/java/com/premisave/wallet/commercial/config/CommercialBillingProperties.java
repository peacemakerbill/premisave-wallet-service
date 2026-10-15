package com.premisave.wallet.commercial.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Subscription billing settings. upcomingDays is how far ahead invoice.upcoming is sent. stuckMinutes is
 * how long an attempt may sit unresolved before the recovery sweep looks at it; it must stay above the
 * five minutes an Idempotency-Key is held while its request runs. batchSize caps the work per scheduler
 * run. clockWaitMs is how long the sandbox advance-clock helper waits for a payment to settle.
 */
@ConfigurationProperties(prefix = "commercial.billing")
public record CommercialBillingProperties(Integer upcomingDays, Integer stuckMinutes, Integer batchSize, Integer clockWaitMs) {

    public CommercialBillingProperties {
        if (upcomingDays == null || upcomingDays < 1 || upcomingDays > 30) upcomingDays = 3;
        if (stuckMinutes == null || stuckMinutes < 6) stuckMinutes = 10;
        if (batchSize == null || batchSize < 1 || batchSize > 1000) batchSize = 100;
        if (clockWaitMs == null || clockWaitMs < 0 || clockWaitMs > 30000) clockWaitMs = 3000;
    }
}
