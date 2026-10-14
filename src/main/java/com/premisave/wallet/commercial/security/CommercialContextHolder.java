package com.premisave.wallet.commercial.security;

import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.exception.CommercialException;

/** Request scoped holder, set by the interceptor and cleared when the request finishes. */
public final class CommercialContextHolder {

    private static final ThreadLocal<CommercialContext> CURRENT = new ThreadLocal<>();

    private CommercialContextHolder() {}

    public static void set(CommercialContext ctx) { CURRENT.set(ctx); }

    public static void clear() { CURRENT.remove(); }

    public static CommercialContext require() {
        CommercialContext ctx = CURRENT.get();
        if (ctx == null) {
            throw new CommercialException(ErrorCode.INTERNAL_ERROR, "Request context was not established");
        }
        return ctx;
    }
}
