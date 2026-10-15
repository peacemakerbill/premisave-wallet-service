package com.premisave.wallet.commercial.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TxTypeTest {

    @Test
    void moneyGoingOutIsADebit() {
        for (TxType t : new TxType[]{TxType.PAYOUT, TxType.SETTLEMENT, TxType.TRANSFER, TxType.REFUND, TxType.FEE}) {
            assertTrue(t.isDebit(), t.name());
        }
        for (TxType t : new TxType[]{TxType.PAYMENT, TxType.SUBSCRIPTION_CHARGE, TxType.ADJUSTMENT}) {
            assertFalse(t.isDebit(), t.name());
        }
    }

    @Test
    void payoutSettlementTransferAndRefundUseAHold() {
        assertTrue(TxType.PAYOUT.usesHold());
        assertTrue(TxType.SETTLEMENT.usesHold());
        assertTrue(TxType.TRANSFER.usesHold());
        assertTrue(TxType.REFUND.usesHold());
        assertFalse(TxType.PAYMENT.usesHold());
        assertFalse(TxType.FEE.usesHold());
    }

    @Test
    void everyTypeHasALabel() {
        for (TxType t : TxType.values()) assertFalse(t.label().isBlank(), t.name());
    }
}
