package com.premisave.wallet.commercial.rail.mpesa;

import com.premisave.wallet.commercial.enums.ResultCode;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.rail.RailOutcome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MpesaResultMapperTest {

    @Test
    void stkSuccessCarriesTheReceipt() {
        RailOutcome o = MpesaResultMapper.stk("0", "The service request is processed successfully.", "QGH7XYZ123");
        assertEquals(TxStatus.SUCCESS, o.status());
        assertEquals(ResultCode.SUCCESS, o.code());
        assertEquals("QGH7XYZ123", o.providerReference());
        assertEquals("0", o.providerCode());
    }

    @Test
    void stkCustomerCancelled() {
        RailOutcome o = MpesaResultMapper.stk("1032", "Request cancelled by user", null);
        assertEquals(TxStatus.FAILED, o.status());
        assertEquals(ResultCode.CUSTOMER_CANCELLED, o.code());
        assertNull(o.providerReference());
    }

    @Test
    void stkCustomerDidNotRespond() {
        RailOutcome o = MpesaResultMapper.stk("1037", "DS timeout user cannot be reached", null);
        assertEquals(TxStatus.TIMED_OUT, o.status());
        assertEquals(ResultCode.CUSTOMER_TIMEOUT, o.code());
    }

    @Test
    void stkInsufficientFundsAndWrongPin() {
        assertEquals(ResultCode.INSUFFICIENT_FUNDS, MpesaResultMapper.stk("1", "balance", null).code());
        assertEquals(ResultCode.DECLINED, MpesaResultMapper.stk("2001", "wrong pin", null).code());
    }

    @Test
    void unknownStkCodeIsAProviderErrorWithSafaricomsOwnWords() {
        RailOutcome o = MpesaResultMapper.stk("9999", "Something new", null);
        assertEquals(TxStatus.FAILED, o.status());
        assertEquals(ResultCode.PROVIDER_ERROR, o.code());
        assertEquals("9999", o.providerCode());
        assertEquals("Something new", o.providerDescription());
    }

    @Test
    void blankDescriptionGetsAPlaceholder() {
        assertEquals("No description from M-Pesa", MpesaResultMapper.stk("9999", " ", null).providerDescription());
    }

    @Test
    void b2cSuccessAndFailure() {
        RailOutcome ok = MpesaResultMapper.b2c("0", "done", "SGL12345");
        assertEquals(TxStatus.SUCCESS, ok.status());
        assertEquals("SGL12345", ok.providerReference());

        RailOutcome float0 = MpesaResultMapper.b2c("1", "The balance is insufficient", null);
        assertEquals(TxStatus.FAILED, float0.status());
        assertEquals(ResultCode.PROVIDER_ERROR, float0.code());

        assertEquals(ResultCode.PROVIDER_ERROR, MpesaResultMapper.b2c("2001", "bad initiator", null).code());
    }

    @Test
    void queueTimeoutMeansNotSent() {
        RailOutcome o = MpesaResultMapper.b2cQueueTimeout();
        assertEquals(TxStatus.TIMED_OUT, o.status());
        assertEquals(ResultCode.TIMED_OUT, o.code());
    }
}
