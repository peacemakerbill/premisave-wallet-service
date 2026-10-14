package com.premisave.wallet.commercial.enums;

/** Identifies a payment rail. The sandbox rail is internal and is reported to merchants as the provider it simulates. */
public enum RailId {
    MPESA, STRIPE, PAYPAL, FLUTTERWAVE, NOWPAYMENTS, PREMISAVE, SANDBOX
}
