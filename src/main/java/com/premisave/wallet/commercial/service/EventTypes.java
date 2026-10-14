package com.premisave.wallet.commercial.service;

import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.enums.TxType;

/** Maps a transaction's type and status to the public event name. */
public final class EventTypes {

    public static final String CALLBACK_TEST = "callback.test";
    public static final String CALLBACK_VERIFICATION = "callback.verification";

    private EventTypes() {}

    /** Null when the combination has no public event (for example a payout that is merely created). */
    public static String forTransaction(CommercialTransaction tx) {
        TxStatus s = tx.getStatus();
        if (tx.getType() == TxType.PAYMENT) {
            return switch (s) {
                case REQUIRES_ACTION -> "payment.requires_action";
                case PENDING -> "payment.pending";
                case SUCCESS -> "payment.succeeded";
                case FAILED -> "payment.failed";
                case TIMED_OUT -> "payment.timed_out";
                case CANCELLED -> "payment.cancelled";
                case REVERSED -> null;
            };
        }
        if (tx.getType() == TxType.PAYOUT) {
            return switch (s) {
                case REQUIRES_ACTION -> "payout.requires_action";
                case PENDING -> "payout.pending";
                case SUCCESS -> "payout.succeeded";
                case FAILED, REVERSED, CANCELLED -> "payout.failed";
                case TIMED_OUT -> "payout.timed_out";
            };
        }
        if (tx.getType() == TxType.SETTLEMENT) {
            return switch (s) {
                case SUCCESS -> "settlement.succeeded";
                case FAILED, TIMED_OUT, CANCELLED, REVERSED -> "settlement.failed";
                case REQUIRES_ACTION, PENDING -> null;
            };
        }
        return null;
    }
}
