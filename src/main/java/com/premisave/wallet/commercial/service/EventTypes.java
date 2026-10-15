package com.premisave.wallet.commercial.service;

import com.premisave.wallet.commercial.entity.CommercialTransaction;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.enums.TxType;

/** Maps a transaction's type and status to the public event name. */
public final class EventTypes {

    public static final String CALLBACK_TEST = "callback.test";
    public static final String CALLBACK_VERIFICATION = "callback.verification";
    public static final String PAYOUT_BATCH_COMPLETED = "payout.batch.completed";
    public static final String BENEFICIARY_CHECK_COMPLETED = "beneficiary_check.completed";
    public static final String DISPUTE_OPENED = "dispute.opened";
    public static final String DISPUTE_WON = "dispute.won";
    public static final String DISPUTE_LOST = "dispute.lost";
    public static final String SUBSCRIPTION_CREATED = "subscription.created";
    public static final String SUBSCRIPTION_ACTIVATED = "subscription.activated";
    public static final String SUBSCRIPTION_PAST_DUE = "subscription.past_due";
    public static final String SUBSCRIPTION_PAUSED = "subscription.paused";
    public static final String SUBSCRIPTION_CANCELLED = "subscription.cancelled";
    public static final String PAYMENT_METHOD_ACTIVATED = "customer.payment_method.activated";
    public static final String INVOICE_UPCOMING = "invoice.upcoming";
    public static final String INVOICE_PAID = "invoice.paid";
    public static final String INVOICE_PAYMENT_FAILED = "invoice.payment_failed";

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
        if (tx.getType() == TxType.REFUND) {
            return switch (s) {
                case SUCCESS -> "refund.succeeded";
                case FAILED, TIMED_OUT, CANCELLED, REVERSED -> "refund.failed";
                case REQUIRES_ACTION, PENDING -> null;
            };
        }
        return null;
    }
}
