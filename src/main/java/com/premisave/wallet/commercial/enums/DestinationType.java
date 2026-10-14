package com.premisave.wallet.commercial.enums;

/** What a payout is sent to. Each maps onto the payment method (and so the rail family) that serves it. */
public enum DestinationType {
    MOBILE_MONEY(MethodType.MOBILE_MONEY),
    BUSINESS_ACCOUNT(MethodType.MOBILE_MONEY),
    BANK_ACCOUNT(MethodType.BANK_TRANSFER),
    PAYPAL(MethodType.PAYPAL),
    CRYPTO(MethodType.CRYPTO),
    PREMISAVE_WALLET(MethodType.PREMISAVE_WALLET);

    private final MethodType method;

    DestinationType(MethodType method) { this.method = method; }

    public MethodType method() { return method; }
}
