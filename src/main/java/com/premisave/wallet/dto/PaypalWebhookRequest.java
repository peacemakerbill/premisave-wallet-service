package com.premisave.wallet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * Minimal parse of PayPal's webhook payload — only the fields Premisave
 * actually needs to identify and reconcile an order. Real PayPal webhook
 * payloads carry many more fields; this DTO intentionally captures just
 * what's used for reconciliation.
 */
@Data
public class PaypalWebhookRequest {

    @JsonProperty("event_type")
    private String eventType;

    @JsonProperty("resource")
    private Resource resource;

    @Data
    public static class Resource {
        @JsonProperty("id")
        private String id; // PayPal Order ID — matches our Transaction.reference

        @JsonProperty("status")
        private String status;

        @JsonProperty("purchase_units")
        private List<PurchaseUnit> purchaseUnits;
    }

    @Data
    public static class PurchaseUnit {
        @JsonProperty("reference_id")
        private String referenceId;

        @JsonProperty("amount")
        private Amount amount;
    }

    @Data
    public static class Amount {
        @JsonProperty("currency_code")
        private String currencyCode;

        @JsonProperty("value")
        private String value;
    }
}