package com.premisave.wallet.commercial.config;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommercialStage3PropertiesTest {

    @Test
    void refundDefaults() {
        assertEquals("1000", new CommercialRefundProperties(null).approvalThresholdUsd().toPlainString());
        assertEquals("1000", new CommercialRefundProperties(new BigDecimal("-1")).approvalThresholdUsd().toPlainString());
        assertEquals("250", new CommercialRefundProperties(new BigDecimal("250")).approvalThresholdUsd().toPlainString());
    }

    @Test
    void disputeDefaults() {
        assertEquals(14, new CommercialDisputeProperties(null).evidenceWindowDays());
        assertEquals(14, new CommercialDisputeProperties(0).evidenceWindowDays());
        assertEquals(30, new CommercialDisputeProperties(30).evidenceWindowDays());
    }
}
