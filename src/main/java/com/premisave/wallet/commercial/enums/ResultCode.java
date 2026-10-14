package com.premisave.wallet.commercial.enums;

/** Provider-neutral outcome codes. Provider specific codes are carried alongside, never instead. */
public enum ResultCode {
    SUCCESS, CUSTOMER_CANCELLED, CUSTOMER_TIMEOUT, INSUFFICIENT_FUNDS, DECLINED, INVALID_DESTINATION,
    BENEFICIARY_MISMATCH, LIMIT_EXCEEDED, PROVIDER_ERROR, TIMED_OUT, REVERSED
}
