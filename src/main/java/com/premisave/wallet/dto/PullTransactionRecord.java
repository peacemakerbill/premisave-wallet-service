package com.premisave.wallet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Matches Safaricom's per-transaction fields from the Pull Transaction Query
 * response. Field names are lowercase per the official spec (transactionId
 * is the one exception — everything else is lowercase, unlike every other
 * M-Pesa API in this codebase, which uses PascalCase).
 * See https://developer.safaricom.co.ke/apis/PullTransaction
 */
@Data
public class PullTransactionRecord {

    @JsonProperty("transactionId")
    private String transactionId;

    @JsonProperty("trxDate")
    private String trxDate;

    @JsonProperty("msisdn")
    private String msisdn;

    @JsonProperty("transactiontype")
    private String transactionType;

    @JsonProperty("billreference")
    private String billReference;

    @JsonProperty("amount")
    private String amount;

    @JsonProperty("organizationname")
    private String organizationName;
}