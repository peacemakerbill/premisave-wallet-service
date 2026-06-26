package com.premisave.wallet.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PayPal v2 Orders API (deposits) + Payouts API (disbursements).
 * Uses OkHttp directly — no heavyweight PayPal SDK.
 */
@Slf4j
@Service
public class PaypalService {

    @Value("${paypal.client-id}")
    private String clientId;

    @Value("${paypal.client-secret}")
    private String clientSecret;

    @Value("${paypal.environment:sandbox}")
    private String environment;

    private final OkHttpClient http = new OkHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String baseUrl() {
        return "sandbox".equalsIgnoreCase(environment)
                ? "https://api-m.sandbox.paypal.com"
                : "https://api-m.paypal.com";
    }

    // ─── OAuth ────────────────────────────────────────────────────────────────

    /**
     * Fetches a short-lived Bearer token.
     * Cache this for ~8 hours in production (PayPal tokens last 9 h).
     */
    public String getAccessToken() {
        String credentials = clientId + ":" + clientSecret;
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

        RequestBody body = new FormBody.Builder()
                .add("grant_type", "client_credentials")
                .build();

        Request request = new Request.Builder()
                .url(baseUrl() + "/v1/oauth2/token")
                .addHeader("Authorization", "Basic " + encoded)
                .post(body)
                .build();

        try (Response response = http.newCall(request).execute()) {
            JsonNode node = objectMapper.readTree(response.body().string());
            return node.path("access_token").asText();
        } catch (Exception e) {
            throw new RuntimeException("Failed to get PayPal access token", e);
        }
    }

    // ─── Deposit (Orders API v2) ──────────────────────────────────────────────

    /**
     * Creates a PayPal Order for wallet deposits.
     * Returns the "approve" link — redirect the user there to authorise payment.
     * After approval, call captureOrder() with the returned order_id.
     *
     * @return Map with "orderId" and "approveUrl"
     */
    public Map<String, String> createOrder(BigDecimal amount, String currency, String idempotencyKey) {
        String token = getAccessToken();

        Map<String, Object> requestBody = Map.of(
                "intent", "CAPTURE",
                "purchase_units", List.of(Map.of(
                        "reference_id", idempotencyKey,
                        "amount", Map.of(
                                "currency_code", currency.toUpperCase(),
                                "value", amount.toPlainString()
                        ),
                        "description", "Premisave wallet deposit"
                ))
        );

        try {
            String json = objectMapper.writeValueAsString(requestBody);
            RequestBody rb = RequestBody.create(json, MediaType.parse("application/json"));

            Request request = new Request.Builder()
                    .url(baseUrl() + "/v2/checkout/orders")
                    .addHeader("Authorization", "Bearer " + token)
                    .addHeader("PayPal-Request-Id", idempotencyKey)
                    .post(rb)
                    .build();

            try (Response response = http.newCall(request).execute()) {
                JsonNode node = objectMapper.readTree(response.body().string());
                String orderId = node.path("id").asText();
                String approveUrl = "";
                for (JsonNode link : node.path("links")) {
                    if ("approve".equals(link.path("rel").asText())) {
                        approveUrl = link.path("href").asText();
                        break;
                    }
                }
                log.info("PayPal Order created: id={}", orderId);
                return Map.of("orderId", orderId, "approveUrl", approveUrl);
            }
        } catch (Exception e) {
            throw new RuntimeException("PayPal createOrder failed: " + e.getMessage(), e);
        }
    }

    /**
     * Captures (completes) a PayPal Order after the user approves it.
     * Call this from your PayPal return/webhook handler.
     *
     * @return PayPal capture ID (use as providerReference)
     */
    public String captureOrder(String orderId) {
        String token = getAccessToken();
        RequestBody rb = RequestBody.create("{}", MediaType.parse("application/json"));

        Request request = new Request.Builder()
                .url(baseUrl() + "/v2/checkout/orders/" + orderId + "/capture")
                .addHeader("Authorization", "Bearer " + token)
                .post(rb)
                .build();

        try (Response response = http.newCall(request).execute()) {
            JsonNode node = objectMapper.readTree(response.body().string());
            String captureId = node.path("purchase_units").get(0)
                    .path("payments").path("captures").get(0)
                    .path("id").asText();
            log.info("PayPal Order captured: orderId={} captureId={}", orderId, captureId);
            return captureId;
        } catch (Exception e) {
            throw new RuntimeException("PayPal captureOrder failed: " + e.getMessage(), e);
        }
    }

    // ─── Disbursement (Payouts API) ───────────────────────────────────────────

    /**
     * Sends money to a PayPal email address (Payouts API).
     * Returns the Payout batch ID.
     */
    public String processPayout(String recipientEmail, BigDecimal amount, String currency) {
        String token = getAccessToken();
        String batchId = UUID.randomUUID().toString();

        Map<String, Object> requestBody = Map.of(
                "sender_batch_header", Map.of(
                        "sender_batch_id", batchId,
                        "email_subject", "You have a payment from Premisave",
                        "email_message", "Your wallet disbursement has been processed."
                ),
                "items", List.of(Map.of(
                        "recipient_type", "EMAIL",
                        "amount", Map.of(
                                "value", amount.toPlainString(),
                                "currency", currency.toUpperCase()
                        ),
                        "receiver", recipientEmail,
                        "note", "Premisave wallet disbursement",
                        "sender_item_id", batchId
                ))
        );

        try {
            String json = objectMapper.writeValueAsString(requestBody);
            RequestBody rb = RequestBody.create(json, MediaType.parse("application/json"));

            Request request = new Request.Builder()
                    .url(baseUrl() + "/v1/payments/payouts")
                    .addHeader("Authorization", "Bearer " + token)
                    .post(rb)
                    .build();

            try (Response response = http.newCall(request).execute()) {
                JsonNode node = objectMapper.readTree(response.body().string());
                String payoutBatchId = node.path("batch_header").path("payout_batch_id").asText();
                log.info("PayPal Payout created: batchId={}", payoutBatchId);
                return payoutBatchId;
            }
        } catch (Exception e) {
            throw new RuntimeException("PayPal payout failed: " + e.getMessage(), e);
        }
    }
}