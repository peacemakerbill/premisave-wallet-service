package com.premisave.wallet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * Matches Safaricom's real B2C / B2B "Result" callback envelope (ResultURL).
 * Distinct from the STK callback shape (Body.stkCallback) — this one is a
 * top-level "Result" object.
 *
 * Success example:
 * {
 *   "Result": {
 *     "ResultType": 0,
 *     "ResultCode": 0,
 *     "ResultDesc": "The service request is processed successfully.",
 *     "OriginatorConversationID": "...",
 *     "ConversationID": "AG_20191219_...",
 *     "TransactionID": "NLJ7RT61SV",
 *     "ResultParameters": {
 *       "ResultParameter": [
 *         {"Key": "TransactionAmount", "Value": 100},
 *         {"Key": "TransactionReceipt", "Value": "NLJ7RT61SV"},
 *         {"Key": "ReceiverPartyPublicName", "Value": "254712345678 - John Doe"}
 *       ]
 *     }
 *   }
 * }
 *
 * On failure, ResultParameters is typically absent — only ResultCode/ResultDesc matter.
 */
@Data
public class MpesaResultCallbackRequest {

    @JsonProperty("Result")
    private Result result;

    @Data
    public static class Result {
        @JsonProperty("ResultType")
        private int resultType;

        @JsonProperty("ResultCode")
        private int resultCode;

        @JsonProperty("ResultDesc")
        private String resultDesc;

        @JsonProperty("OriginatorConversationID")
        private String originatorConversationID;

        @JsonProperty("ConversationID")
        private String conversationID;

        @JsonProperty("TransactionID")
        private String transactionID;

        @JsonProperty("ResultParameters")
        private ResultParameters resultParameters;
    }

    @Data
    public static class ResultParameters {
        @JsonProperty("ResultParameter")
        private List<ResultParameter> resultParameter;
    }

    @Data
    public static class ResultParameter {
        @JsonProperty("Key")
        private String key;

        @JsonProperty("Value")
        private Object value;
    }
}