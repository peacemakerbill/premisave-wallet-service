package com.premisave.wallet.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.premisave.wallet.exception.PaypalCaptureException;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * PayPal v2 Orders API (deposits) + Payouts API (disbursements).
 * Uses OkHttp directly — no heavyweight PayPal SDK.
 *
 * PayPal's official generated SDK (com.paypal.sdk:paypal-server-sdk) was
 * evaluated and deliberately NOT adopted: it doesn't cover the Payouts API
 * at all, so using it would still require hand-rolled OkHttp calls for
 * payouts anyway — splitting PayPal integration across two different HTTP
 * clients and two different error-handling styles for no real benefit.
 * Keeping everything on OkHttp/Jackson also preserves the custom
 * PaypalCaptureException.isAlreadyCaptured() distinction that
 * DepositService's reconciliation logic depends on.
 *
 * All amounts handled by this service are in USD (see DepositService and
 * DisbursementService for the KES<->USD conversion applied around these
 * calls, since PayPal doesn't support KES as a transaction currency).
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

    /**
     * PayPal Webhook ID — from Developer Dashboard → your app → Webhooks →
     * your registered webhook endpoint. Required to verify that incoming
     * /payments/paypal/webhook calls actually originated from PayPal (see
     * verifyWebhookSignature) rather than trusting the request body
     * outright. Optional at startup (no @Value default failure) so this
     * service still works for Orders/Payouts testing before a webhook is
     * registered — but PaymentCallbackController will reject all webhook
     * calls until this is set.
     */
    @Value("${paypal.webhook-id:}")
    private String webhookId;

    private final OkHttpClient http = new OkHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String baseUrl() {
        return "sandbox".equalsIgnoreCase(environment)
                ? "https://api-m.sandbox.paypal.com"
                : "https://api-m.paypal.com";
    }

    // ─── OAuth (cached) ──────────────────────────────────────────────────────

    /**
     * PayPal client-credentials tokens last ~9 hours (32400s) in practice,
     * though the actual expires_in is read from the response rather than
     * assumed. Previously this fetched a brand-new token on every single
     * call — correct but wasteful, adding a full extra HTTP round trip to
     * every Orders/Payouts operation. Cached here the same way
     * MpesaService caches its OAuth token, refreshing a minute before
     * actual expiry to avoid races.
     */
    private record CachedToken(String token, Instant expiresAt) {}
    private final AtomicReference<CachedToken> tokenCache = new AtomicReference<>();

    public synchronized String getAccessToken() {
        CachedToken cached = tokenCache.get();
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
            return cached.token();
        }

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
            String responseBody = response.body().string();
            JsonNode node = objectMapper.readTree(responseBody);
            String token = node.path("access_token").asText();
            int expiresIn = node.path("expires_in").asInt(32400); // ~9h typical

            if (!response.isSuccessful() || token.isBlank()) {
                throw new RuntimeException("PayPal OAuth failed (" + response.code() + "): " + responseBody);
            }

            CachedToken fresh = new CachedToken(token, Instant.now().plusSeconds(Math.max(60, expiresIn - 60)));
            tokenCache.set(fresh);
            log.info("PayPal access token refreshed, valid for ~{}s", expiresIn);
            return token;
        } catch (Exception e) {
            throw new RuntimeException("Failed to get PayPal access token", e);
        }
    }

    // ─── Deposit (Orders API v2) ──────────────────────────────────────────────

    /**
     * Creates a PayPal Order for wallet deposits, in USD.
     * Returns the "approve" link — redirect the user there to authorise payment.
     * After approval, call captureOrder() with the returned order_id.
     *
     * @return Map with "orderId" and "approveUrl"
     */
    public Map<String, String> createOrder(BigDecimal usdAmount, String currency, String idempotencyKey) {
        String token = getAccessToken();

        Map<String, Object> requestBody = Map.of(
                "intent", "CAPTURE",
                "purchase_units", List.of(Map.of(
                        "reference_id", idempotencyKey,
                        "amount", Map.of(
                                "currency_code", currency.toUpperCase(),
                                "value", usdAmount.toPlainString()
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
                String responseBody = response.body().string();
                JsonNode node = objectMapper.readTree(responseBody);

                if (!response.isSuccessful()) {
                    throw new RuntimeException("PayPal createOrder failed (" + response.code() + "): " + responseBody);
                }

                String orderId = node.path("id").asText();
                String approveUrl = "";
                for (JsonNode link : node.path("links")) {
                    if ("approve".equals(link.path("rel").asText())) {
                        approveUrl = link.path("href").asText();
                        break;
                    }
                }

                if (orderId.isBlank() || approveUrl.isBlank()) {
                    throw new RuntimeException("PayPal createOrder response missing id/approve link: " + responseBody);
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
     * @throws PaypalCaptureException if PayPal rejects the capture — check
     *         isAlreadyCaptured() to distinguish idempotent no-ops from
     *         genuine failures.
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
            String responseBody = response.body().string();
            JsonNode node = objectMapper.readTree(responseBody);

            if (!response.isSuccessful()) {
                String issue = "";
                JsonNode details = node.path("details");
                if (details.isArray() && details.size() > 0) {
                    issue = details.get(0).path("issue").asText("");
                }
                throw new PaypalCaptureException(orderId, issue, responseBody);
            }

            JsonNode captures = node.path("purchase_units").get(0).path("payments").path("captures");
            if (captures == null || !captures.isArray() || captures.isEmpty()) {
                throw new RuntimeException("PayPal capture response missing captures array: " + responseBody);
            }

            String captureId = captures.get(0).path("id").asText();
            log.info("PayPal Order captured: orderId={} captureId={}", orderId, captureId);
            return captureId;
        } catch (PaypalCaptureException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("PayPal captureOrder failed: " + e.getMessage(), e);
        }
    }

    // ─── Disbursement (Payouts API) ───────────────────────────────────────────

    /**
     * Sends money to a PayPal email address (Payouts API), in USD.
     * Returns the Payout batch ID.
     */
    public String processPayout(String recipientEmail, BigDecimal usdAmount, String currency) {
        String token = getAccessToken();
        String senderBatchId = UUID.randomUUID().toString();

        Map<String, Object> requestBody = Map.of(
                "sender_batch_header", Map.of(
                        "sender_batch_id", senderBatchId,
                        "email_subject", "You have a payment from Premisave",
                        "email_message", "Your wallet disbursement has been processed."
                ),
                "items", List.of(Map.of(
                        "recipient_type", "EMAIL",
                        "amount", Map.of(
                                "value", usdAmount.toPlainString(),
                                "currency", currency.toUpperCase()
                        ),
                        "receiver", recipientEmail,
                        "note", "Premisave wallet disbursement",
                        "sender_item_id", senderBatchId
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
                String responseBody = response.body().string();
                JsonNode node = objectMapper.readTree(responseBody);

                if (!response.isSuccessful()) {
                    throw new RuntimeException("PayPal payout failed (" + response.code() + "): " + responseBody);
                }

                String payoutBatchId = node.path("batch_header").path("payout_batch_id").asText();
                if (payoutBatchId.isBlank()) {
                    throw new RuntimeException("PayPal payout response missing payout_batch_id: " + responseBody);
                }

                log.info("PayPal Payout created: batchId={}", payoutBatchId);
                return payoutBatchId;
            }
        } catch (Exception e) {
            throw new RuntimeException("PayPal payout failed: " + e.getMessage(), e);
        }
    }

    // ─── Webhook signature verification ───────────────────────────────────────

    /**
     * Verifies that an incoming /payments/paypal/webhook request genuinely
     * came from PayPal, using PayPal's Verify Webhook Signature API rather
     * than trusting the request body outright. Without this, anyone who
     * discovers the webhook URL could POST a fake CHECKOUT.ORDER.APPROVED
     * event and trigger a deposit confirmation for an order ID they don't
     * own (bounded in practice since the order still has to genuinely
     * exist and be capturable on PayPal's side — but this closes the gap
     * properly instead of relying on that as the only defense).
     *
     * Requires paypal.webhook-id to be configured (see application.yml) —
     * obtained from Developer Dashboard → your app → Webhooks after
     * registering https://<your-domain>/payments/paypal/webhook there.
     *
     * @param headers     the incoming request's HTTP headers (case-sensitive
     *                    keys as sent by PayPal: PAYPAL-TRANSMISSION-ID,
     *                    PAYPAL-TRANSMISSION-TIME, PAYPAL-CERT-URL,
     *                    PAYPAL-AUTH-ALGO, PAYPAL-TRANSMISSION-SIG)
     * @param rawBody     the exact raw request body PayPal sent, unmodified
     * @return true if PayPal confirms the signature is valid
     */
    public boolean verifyWebhookSignature(Map<String, String> headers, String rawBody) {
        if (webhookId == null || webhookId.isBlank()) {
            log.error("PayPal webhook signature verification skipped — paypal.webhook-id is not configured. " +
                    "Rejecting webhook as unverifiable.");
            return false;
        }

        String token = getAccessToken();

        JsonNode eventBodyNode;
        try {
            eventBodyNode = objectMapper.readTree(rawBody);
        } catch (Exception e) {
            log.error("PayPal webhook verification: failed to parse event body as JSON", e);
            return false;
        }

        Map<String, Object> verifyRequest = Map.of(
                "transmission_id", headers.getOrDefault("PAYPAL-TRANSMISSION-ID", ""),
                "transmission_time", headers.getOrDefault("PAYPAL-TRANSMISSION-TIME", ""),
                "cert_url", headers.getOrDefault("PAYPAL-CERT-URL", ""),
                "auth_algo", headers.getOrDefault("PAYPAL-AUTH-ALGO", ""),
                "transmission_sig", headers.getOrDefault("PAYPAL-TRANSMISSION-SIG", ""),
                "webhook_id", webhookId,
                "webhook_event", eventBodyNode
        );

        try {
            String json = objectMapper.writeValueAsString(verifyRequest);
            RequestBody rb = RequestBody.create(json, MediaType.parse("application/json"));

            Request request = new Request.Builder()
                    .url(baseUrl() + "/v1/notifications/verify-webhook-signature")
                    .addHeader("Authorization", "Bearer " + token)
                    .post(rb)
                    .build();

            try (Response response = http.newCall(request).execute()) {
                String responseBody = response.body().string();

                if (!response.isSuccessful()) {
                    log.error("PayPal webhook verification call failed ({}): {}", response.code(), responseBody);
                    return false;
                }

                JsonNode node = objectMapper.readTree(responseBody);
                String status = node.path("verification_status").asText("");
                boolean valid = "SUCCESS".equals(status);

                if (!valid) {
                    log.warn("PayPal webhook signature verification returned status={}", status);
                }
                return valid;
            }
        } catch (Exception e) {
            log.error("PayPal webhook signature verification failed", e);
            return false;
        }
    }
}