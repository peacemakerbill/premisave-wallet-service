package com.premisave.wallet.commercial.rail;

import com.premisave.wallet.commercial.enums.ResultCode;
import com.premisave.wallet.commercial.enums.TxStatus;

import java.util.Map;

/**
 * What a rail says right after accepting a request. Usually REQUIRES_ACTION or
 * PENDING; a rail that finishes at once (an internal ledger move) can answer
 * with a final status and result.
 */
public record RailResponse(TxStatus status, Map<String, Object> nextAction, String providerReference,
                           ResultCode code, String description) {

    public static RailResponse pending(String providerReference) {
        return new RailResponse(TxStatus.PENDING, null, providerReference, null, null);
    }

    public static RailResponse requiresAction(Map<String, Object> nextAction, String providerReference) {
        return new RailResponse(TxStatus.REQUIRES_ACTION, nextAction, providerReference, null, null);
    }
}
