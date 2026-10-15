package com.premisave.wallet.commercial.batch;

import com.premisave.wallet.commercial.batch.BatchRules.Counters;
import com.premisave.wallet.commercial.enums.TxStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchRulesTest {

    @Test
    void everyStatusLandsInOneCounter() {
        assertEquals("pending", BatchRules.bucket(TxStatus.PENDING));
        assertEquals("pending", BatchRules.bucket(TxStatus.REQUIRES_ACTION));
        assertEquals("success", BatchRules.bucket(TxStatus.SUCCESS));
        assertEquals("failed", BatchRules.bucket(TxStatus.FAILED));
        assertEquals("failed", BatchRules.bucket(TxStatus.CANCELLED));
        assertEquals("failed", BatchRules.bucket(TxStatus.REVERSED));
        assertEquals("timedOut", BatchRules.bucket(TxStatus.TIMED_OUT));
        for (TxStatus s : TxStatus.values()) {
            BatchRules.bucket(s);
        }
    }

    @Test
    void aBatchIsFinishedOnlyWhenNothingIsPendingAndSomethingWasAccepted() {
        assertFalse(BatchRules.isFinished(new Counters(1, 5, 0, 0)));
        assertTrue(BatchRules.isFinished(new Counters(0, 5, 1, 0)));
        assertFalse(BatchRules.isFinished(new Counters(0, 0, 0, 0)));
    }

    @Test
    void completedMeansEveryItemWasPaid() {
        assertEquals(PayoutBatch.Status.COMPLETED, BatchRules.finalStatus(new Counters(0, 3, 0, 0), 0));
        assertEquals(PayoutBatch.Status.COMPLETED_WITH_FAILURES, BatchRules.finalStatus(new Counters(0, 2, 1, 0), 0));
        assertEquals(PayoutBatch.Status.COMPLETED_WITH_FAILURES, BatchRules.finalStatus(new Counters(0, 2, 0, 1), 0));
        assertEquals(PayoutBatch.Status.COMPLETED_WITH_FAILURES, BatchRules.finalStatus(new Counters(0, 3, 0, 0), 1),
                "an item turned away at intake counts as a failure");
    }

    @Test
    void indexKeysSortInRequestOrder() {
        assertEquals("0001", BatchRules.indexKey(1));
        assertEquals("0500", BatchRules.indexKey(500));
        assertTrue(BatchRules.indexKey(2).compareTo(BatchRules.indexKey(10)) < 0);
    }

    @Test
    void theUniqueKeySeparatesMerchantsAndEnvironments() {
        String a = PayoutBatch.uniqueKeyFor("mrc_1", true, "PAYROLL");
        assertFalse(a.equals(PayoutBatch.uniqueKeyFor("mrc_1", false, "PAYROLL")));
        assertFalse(a.equals(PayoutBatch.uniqueKeyFor("mrc_2", true, "PAYROLL")));
        assertEquals(a, PayoutBatch.uniqueKeyFor("mrc_1", true, "PAYROLL"));
    }
}
