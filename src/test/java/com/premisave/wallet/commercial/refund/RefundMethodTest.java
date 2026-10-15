package com.premisave.wallet.commercial.refund;

import com.premisave.wallet.commercial.enums.MethodType;
import com.premisave.wallet.commercial.rail.RailRefundMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefundMethodTest {

    @Test
    void aRailThatCanRefundWinsOverTheFallbacks() {
        assertEquals(RefundMethod.PROVIDER_REFUND, RefundMethod.choose(MethodType.CARD, RailRefundMode.REFUND).get());
        assertEquals(RefundMethod.PROVIDER_REFUND, RefundMethod.choose(MethodType.MOBILE_MONEY, RailRefundMode.REFUND).get());
        assertEquals(RefundMethod.PROVIDER_REVERSAL, RefundMethod.choose(MethodType.MOBILE_MONEY, RailRefundMode.REVERSAL).get());
    }

    @Test
    void withoutRailSupportMobileMoneyIsPaidBackAndWalletsAreCredited() {
        assertEquals(RefundMethod.PAYOUT_TO_CUSTOMER, RefundMethod.choose(MethodType.MOBILE_MONEY, RailRefundMode.NONE).get());
        assertEquals(RefundMethod.WALLET_CREDIT, RefundMethod.choose(MethodType.PREMISAVE_WALLET, RailRefundMode.NONE).get());
    }

    @Test
    void cardsPaypalBankAndCryptoHaveNoFallback() {
        for (MethodType m : new MethodType[]{MethodType.CARD, MethodType.PAYPAL, MethodType.BANK_TRANSFER, MethodType.CRYPTO}) {
            assertFalse(RefundMethod.choose(m, RailRefundMode.NONE).isPresent(), m.name());
        }
    }

    @Test
    void onlyProviderMethodsAreSentToTheProvider() {
        assertTrue(RefundMethod.PROVIDER_REFUND.sentToProvider());
        assertTrue(RefundMethod.PROVIDER_REVERSAL.sentToProvider());
        assertFalse(RefundMethod.PAYOUT_TO_CUSTOMER.sentToProvider());
        assertFalse(RefundMethod.WALLET_CREDIT.sentToProvider());
    }
}
