package com.premisave.wallet.commercial.rail;

import com.premisave.wallet.commercial.enums.Direction;
import com.premisave.wallet.commercial.enums.ResultCode;
import com.premisave.wallet.commercial.enums.TxStatus;

import java.util.Optional;

/**
 * Test amounts for the sandbox. The last two digits of the USD amount choose
 * the result, so a developer can exercise every path without a helper call:
 * .01 insufficient funds, .02 cancelled (collection) or invalid destination
 * (payout), .03 timeout, .04 declined, .05 stays pending until simulated,
 * and any other amount succeeds.
 */
public final class SandboxOutcomes {

    private SandboxOutcomes() {}

    /** Empty means "leave it pending until it is simulated". */
    public static Optional<RailOutcome> forAmount(long usdCents, Direction direction) {
        int digits = (int) Math.floorMod(usdCents, 100L);
        return switch (digits) {
            case 5 -> Optional.empty();
            case 1 -> Optional.of(RailOutcome.of(TxStatus.FAILED, ResultCode.INSUFFICIENT_FUNDS, "Insufficient funds"));
            case 2 -> direction == Direction.COLLECTION
                    ? Optional.of(RailOutcome.of(TxStatus.FAILED, ResultCode.CUSTOMER_CANCELLED, "Customer cancelled"))
                    : Optional.of(RailOutcome.of(TxStatus.FAILED, ResultCode.INVALID_DESTINATION, "Destination is not valid"));
            case 3 -> Optional.of(RailOutcome.of(TxStatus.TIMED_OUT,
                    direction == Direction.COLLECTION ? ResultCode.CUSTOMER_TIMEOUT : ResultCode.TIMED_OUT, "Timed out"));
            case 4 -> Optional.of(RailOutcome.of(TxStatus.FAILED, ResultCode.DECLINED, "Declined"));
            default -> Optional.of(RailOutcome.of(TxStatus.SUCCESS, ResultCode.SUCCESS,
                    direction == Direction.COLLECTION ? "Payment completed" : "Payout completed"));
        };
    }
}
