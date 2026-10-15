package com.premisave.wallet.commercial.rail;

/**
 * How a rail can give a customer's money back for a payment it collected. NONE means the rail has
 * no native way, and the refund falls back to what the payment method allows (a payout to the
 * customer's phone, a credit to their Premisave wallet) or is refused.
 */
public enum RailRefundMode {
    NONE, REFUND, REVERSAL
}
