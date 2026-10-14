package com.premisave.wallet.commercial.rail;

import com.premisave.wallet.commercial.enums.ResultCode;

/**
 * A rail could not take the request. UNAVAILABLE means nothing reached the
 * provider (safe to report as 503, nothing was debited). REJECTED means the
 * provider refused it with a reason.
 */
public class RailException extends RuntimeException {

    public enum Kind { UNAVAILABLE, REJECTED }

    private final Kind kind;
    private final ResultCode code;

    public RailException(Kind kind, ResultCode code, String message) {
        super(message);
        this.kind = kind;
        this.code = code;
    }

    public Kind kind() { return kind; }

    public ResultCode code() { return code; }
}
