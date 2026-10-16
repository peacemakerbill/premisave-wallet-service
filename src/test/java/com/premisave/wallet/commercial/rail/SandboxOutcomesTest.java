package com.premisave.wallet.commercial.rail;

import com.premisave.wallet.commercial.enums.Direction;
import com.premisave.wallet.commercial.enums.ResultCode;
import com.premisave.wallet.commercial.enums.TxStatus;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SandboxOutcomesTest {

    private static RailOutcome of(long cents, Direction d) {
        return SandboxOutcomes.forAmount(cents, d).orElseThrow();
    }

    @Test
    void roundAmountsSucceed() {
        assertEquals(TxStatus.SUCCESS, of(2500, Direction.COLLECTION).status());
        assertEquals(TxStatus.SUCCESS, of(100, Direction.PAYOUT).status());
        assertEquals(TxStatus.SUCCESS, of(2575, Direction.COLLECTION).status());
    }

    @Test
    void endingZeroOneIsInsufficientFunds() {
        RailOutcome o = of(2501, Direction.COLLECTION);
        assertEquals(TxStatus.FAILED, o.status());
        assertEquals(ResultCode.INSUFFICIENT_FUNDS, o.code());
    }

    @Test
    void endingZeroTwoDependsOnDirection() {
        assertEquals(ResultCode.CUSTOMER_CANCELLED, of(2502, Direction.COLLECTION).code());
        assertEquals(ResultCode.INVALID_DESTINATION, of(2502, Direction.PAYOUT).code());
    }

    @Test
    void endingZeroThreeTimesOut() {
        assertEquals(TxStatus.TIMED_OUT, of(2503, Direction.COLLECTION).status());
        assertEquals(ResultCode.CUSTOMER_TIMEOUT, of(2503, Direction.COLLECTION).code());
        assertEquals(ResultCode.TIMED_OUT, of(2503, Direction.PAYOUT).code());
    }

    @Test
    void endingZeroFourIsDeclined() {
        assertEquals(ResultCode.DECLINED, of(2504, Direction.PAYOUT).code());
    }

    @Test
    void endingZeroFiveStaysPending() {
        assertTrue(SandboxOutcomes.forAmount(2505, Direction.COLLECTION).equals(Optional.empty()));
    }
}
