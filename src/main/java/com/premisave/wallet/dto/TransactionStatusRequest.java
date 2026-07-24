package com.premisave.wallet.dto;

import lombok.Data;

/**
 * At least one of transactionId or originatorConversationId must be provided —
 * validated in MpesaOperationsService rather than via annotations, since
 * "either/or" isn't expressible with simple bean validation.
 */
@Data
public class TransactionStatusRequest {

    /** M-Pesa Receipt Number of the transaction being queried. */
    private String transactionId;

    /** Alternative to transactionId — the OriginatorConversationID from the original request's response. */
    private String originatorConversationId;

    private String remarks;

    private String occasion;
}