package com.premisave.wallet.commercial.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Refund settings. A refund above approvalThresholdUsd is flagged requiresApproval; until the
 * approval workflow is decided the flag is informational and the refund proceeds.
 */
@ConfigurationProperties(prefix = "commercial.refunds")
public record CommercialRefundProperties(BigDecimal approvalThresholdUsd) {

    public CommercialRefundProperties {
        if (approvalThresholdUsd == null || approvalThresholdUsd.signum() < 0) approvalThresholdUsd = new BigDecimal("1000");
    }
}
