package com.premisave.wallet.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * Matches Safaricom's real B2C / B2B / B2Pochi "Result" callback envelope
 * (ResultURL). Distinct from the STK callback shape (Body.stkCallback) —
 * this one is a top-level "Result" object.
 *
 * @JsonIgnoreProperties(ignoreUnknown = true) is applied on every nested
 * class here (not just the root) — Jackson's ignoreUnknown setting is
 * per-class, so a field like "ReferenceData" nested inside Result would
 * still fail deserialization if only the outer class ignored unknowns.
 *
 * This was previously missing, which caused every B2C/B2B/B2Pochi/Balance/
 * TransactionStatus/Reversal ResultURL callback to be rejected with
 * HttpMessageNotReadableException (400) before PaymentCallbackController's
 * handler methods ever ran — Safaricom's actual payload includes a
 * ReferenceData object this class didn't model. Safaricom retries
 * indefinitely on a non-200, which is what caused the repeated callback
 * attempts visible in the zrok tunnel logs.
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
 *     },
 *     "ReferenceData": {
 *       "ReferenceItem": {"Key": "QueueTimeoutURL", "Value": "..."}
 *     }
 *   }
 * }
 *
 * On failure, ResultParameters/ReferenceData are often absent — only
 * ResultCode/ResultDesc matter.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MpesaResultCallbackRequest {

    @JsonProperty("Result")
    private Result result;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
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

        // ReferenceData is intentionally NOT mapped — nothing in this
        // service currently needs it (it's typically just an echo of the
        // QueueTimeoutURL that was configured for the request). It's still
        // sent by Safaricom on most callbacks, which is why ignoreUnknown
        // above matters: this field must be tolerated, not necessarily read.
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResultParameters {
        @JsonProperty("ResultParameter")
        private List<ResultParameter> resultParameter;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResultParameter {
        @JsonProperty("Key")
        private String key;

        @JsonProperty("Value")
        private Object value;
    }
}