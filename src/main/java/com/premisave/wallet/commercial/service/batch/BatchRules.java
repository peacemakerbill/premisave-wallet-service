package com.premisave.wallet.commercial.service.batch;

import com.premisave.wallet.commercial.entity.batch.PayoutBatch;
import com.premisave.wallet.commercial.enums.TxStatus;

/** The small decisions about a batch, kept free of the database so they can be tested directly. */
public final class BatchRules {

    private BatchRules() {}

    /** How many of a batch's payouts are in each state. pending includes payouts not yet sent. */
    public record Counters(long pending, long success, long failed, long timedOut) {
        public long total() { return pending + success + failed + timedOut; }
    }

    /** Which counter a payout status belongs to. */
    public static String bucket(TxStatus s) {
        return switch (s) {
            case REQUIRES_ACTION, PENDING -> "pending";
            case SUCCESS -> "success";
            case FAILED, CANCELLED, REVERSED -> "failed";
            case TIMED_OUT -> "timedOut";
        };
    }

    /** A batch is finished when nothing is pending. A batch with no payouts at all is not finished here (it was rejected). */
    public static boolean isFinished(Counters c) {
        return c.total() > 0 && c.pending() == 0;
    }

    /** COMPLETED only when every item was paid; any item that failed, timed out or was turned away makes it COMPLETED_WITH_FAILURES. */
    public static PayoutBatch.Status finalStatus(Counters c, int rejectedAtIntake) {
        return c.failed() + c.timedOut() > 0 || rejectedAtIntake > 0
                ? PayoutBatch.Status.COMPLETED_WITH_FAILURES : PayoutBatch.Status.COMPLETED;
    }

    /** Zero padded so items sort in request order even though the index is stored as text. */
    public static String indexKey(int index) {
        return String.format("%04d", index);
    }
}
