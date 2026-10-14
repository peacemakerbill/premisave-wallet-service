package com.premisave.wallet.commercial.enums;

/** Stable machine readable codes carried in data.code of every error response. */
public enum ErrorCode {
    VALIDATION_FAILED(400),
    INVALID_CALLBACK_URL(400),
    ENVIRONMENT_MISMATCH(400),
    UNAUTHENTICATED(401),
    MERCHANT_SUSPENDED(403),
    APP_SUSPENDED(403),
    LIVE_NOT_ENABLED(403),
    METHOD_NOT_ENABLED(403),
    NOT_FOUND(404),
    MERCHANT_NOT_FOUND(404),
    APP_NOT_FOUND(404),
    DUPLICATE_REQUEST(409),
    REQUEST_IN_PROGRESS(409),
    INVALID_STATE(409),
    INSUFFICIENT_BALANCE(422),
    LIMIT_EXCEEDED(422),
    BENEFICIARY_NOT_VERIFIED(422),
    METHOD_UNAVAILABLE(422),
    CALLBACK_REGISTRATION_LIMIT(422),
    CALLBACK_NOT_VERIFIED(422),
    RATE_LIMITED(429),
    INTERNAL_ERROR(500),
    PROVIDER_UNAVAILABLE(503);

    private final int httpStatus;

    ErrorCode(int httpStatus) { this.httpStatus = httpStatus; }

    public int httpStatus() { return httpStatus; }
}
