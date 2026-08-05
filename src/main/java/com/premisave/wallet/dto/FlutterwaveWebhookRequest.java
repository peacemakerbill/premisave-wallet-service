package com.premisave.wallet.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * Flutterwave sends two structurally different payloads under the same
 * webhook endpoint depending on "event": "charge.completed" (deposits) and
 * "transfer.completed" (disbursements). Rather than modeling both shapes
 * strictly and keeping them in sync with Flutterwave's docs, eventData is
 * left as a raw JsonNode — PaymentCallbackController reads the specific
 * fields it needs per event type, same approach already used for PayPal's
 * PAYMENT.PAYOUTS-ITEM.* backstop parsing.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class FlutterwaveWebhookRequest {

    @JsonProperty("event")
    private String event; // "charge.completed" | "transfer.completed"

    @JsonProperty("data")
    private JsonNode eventData;
}