package com.premisave.wallet.commercial.enums;

import java.util.EnumSet;
import java.util.Set;

public enum TxStatus {
    REQUIRES_ACTION, PENDING, SUCCESS, FAILED, TIMED_OUT, CANCELLED, REVERSED;

    private static final Set<TxStatus> FINAL = EnumSet.of(SUCCESS, FAILED, TIMED_OUT, CANCELLED, REVERSED);
    /** States a transaction can still move out of. SUCCESS is not final for payouts that may later be reversed. */
    public static final Set<TxStatus> OPEN = EnumSet.of(REQUIRES_ACTION, PENDING);

    public boolean isFinal() { return FINAL.contains(this); }
}
