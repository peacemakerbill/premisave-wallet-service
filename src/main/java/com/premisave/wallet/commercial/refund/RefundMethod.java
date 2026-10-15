package com.premisave.wallet.commercial.refund;

import com.premisave.wallet.commercial.enums.MethodType;
import com.premisave.wallet.commercial.rail.RailRefundMode;

import java.util.Optional;

/** How the customer gets their money back. Chosen from what the original rail and payment method can do. */
public enum RefundMethod {
    PROVIDER_REFUND, PROVIDER_REVERSAL, PAYOUT_TO_CUSTOMER, WALLET_CREDIT;

    /**
     * A rail that can refund or reverse a payment itself always wins, because the provider then
     * returns the money by the route it came. Otherwise mobile money is paid back to the customer's
     * phone and Premisave wallet payments are credited back. Cards and PayPal need the provider, and
     * bank transfers and crypto have no safe return address, so those have no method here.
     */
    public static Optional<RefundMethod> choose(MethodType payment, RailRefundMode railMode) {
        if (railMode == RailRefundMode.REVERSAL) return Optional.of(PROVIDER_REVERSAL);
        if (railMode == RailRefundMode.REFUND) return Optional.of(PROVIDER_REFUND);
        return switch (payment) {
            case MOBILE_MONEY -> Optional.of(PAYOUT_TO_CUSTOMER);
            case PREMISAVE_WALLET -> Optional.of(WALLET_CREDIT);
            default -> Optional.empty();
        };
    }

    public boolean sentToProvider() {
        return this == PROVIDER_REFUND || this == PROVIDER_REVERSAL;
    }
}
