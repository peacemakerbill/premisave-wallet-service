package com.premisave.wallet.commercial.rail;

import com.premisave.wallet.commercial.enums.ResultCode;
import com.premisave.wallet.commercial.enums.TxStatus;

/** A rail's later answer about a transaction: from a webhook, a poll or a simulation. */
public record RailOutcome(TxStatus status, ResultCode code, String description, String providerCode,
                          String providerDescription, String providerReference) {

    public static RailOutcome of(TxStatus status, ResultCode code, String description) {
        return new RailOutcome(status, code, description, null, null, null);
    }
}
