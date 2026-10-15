package com.premisave.wallet.commercial.rail.nowpayments;

import com.premisave.wallet.commercial.enums.ResultCode;
import com.premisave.wallet.commercial.enums.TxStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NowPaymentsMapperTest {

    @Test
    void finishedIsPaid() {
        assertEquals(TxStatus.SUCCESS, NowPaymentsMapper.payment("finished", "p1").status());
        assertEquals(TxStatus.SUCCESS, NowPaymentsMapper.payment("FINISHED", "p1").status());
    }

    @Test
    void failedAndRefundedFail() {
        assertEquals(TxStatus.FAILED, NowPaymentsMapper.payment("failed", "p1").status());
        assertEquals(ResultCode.REVERSED, NowPaymentsMapper.payment("refunded", "p1").code());
    }

    @Test
    void expiredTimesOut() {
        assertEquals(TxStatus.TIMED_OUT, NowPaymentsMapper.payment("expired", "p1").status());
        assertEquals(ResultCode.CUSTOMER_TIMEOUT, NowPaymentsMapper.payment("expired", "p1").code());
    }

    @Test
    void inProgressStatesStayOpen() {
        for (String s : new String[]{"waiting", "confirming", "confirmed", "sending", "partially_paid", "something_new"}) {
            assertNull(NowPaymentsMapper.payment(s, "p1"));
        }
        assertNull(NowPaymentsMapper.payment(null, "p1"));
    }

    @Test
    void partialPaymentIsRecognised() {
        assertTrue(NowPaymentsMapper.partiallyPaid("partially_paid"));
        assertFalse(NowPaymentsMapper.partiallyPaid("finished"));
        assertFalse(NowPaymentsMapper.partiallyPaid(null));
    }
}
