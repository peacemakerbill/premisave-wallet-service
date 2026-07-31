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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * PayPal v2 Orders API (deposits, with Vault support) + Payouts API
 * (disbursements). Uses OkHttp directly — no heavyweight PayPal SDK.
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

    @Value("${paypal.webhook-id:}")
    private String webhookId;

    @Value("${paypal.return-url}")
    private String returnUrl;

    @Value("${paypal.cancel-url}")
    private String cancelUrl;

    private final OkHttpClient http = new OkHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String baseUrl() {
        return "sandbox".equalsIgnoreCase(environment)
                ? "https://api-m.sandbox.paypal.com"
                : "https://api-m.paypal.com";
    }

    // ─── OAuth (cached) ──────────────────────────────────────────────────────

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
            int expiresIn = node.path("expires_in").asInt(32400);

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

    // ─── Deposit (Orders API v2, with Vault support) ──────────────────────────

    /**
     * Result of createOrder. approveUrl is null when no payer action is
     * required. When PayPal captures the order synchronously as part of
     * order creation itself — which happens when an existing vault_id is
     * reused and no re-authentication is needed — status will be
     * "COMPLETED" and captureId/vaultId/etc. will be populated from that
     * same response, so the caller can credit the wallet directly instead
     * of calling captureOrder() again (which would otherwise fail with
     * ORDER_ALREADY_CAPTURED — see DepositService.initiatePaypalDeposit).
     */
    public record CreateOrderResult(
            String orderId,
            String approveUrl,
            String status,
            String captureId,
            String vaultId,
            String customerId,
            String payerEmail,
            String vaultStatus
    ) {}

    /** Result of captureOrder / getOrder. Fields other than captureId are null unless vaulted. */
    public record CaptureResult(
            String captureId,
            String vaultId,
            String customerId,
            String payerEmail,
            String vaultStatus
    ) {}

    public CreateOrderResult createOrder(BigDecimal usdAmount, String currency, String idempotencyKey,
                                          String existingVaultId, String existingCustomerId,
                                          boolean requestVaulting) {
        String token = getAccessToken();

        Map<String, Object> purchaseUnit = Map.of(
                "reference_id", idempotencyKey,
                "amount", Map.of(
                        "currency_code", currency.toUpperCase(),
                        "value", usdAmount.toPlainString()
                ),
                "description", "Premisave wallet deposit"
        );

        Map<String, Object> experienceContext = Map.of(
                "return_url", returnUrl,
                "cancel_url", cancelUrl
        );

        Map<String, Object> requestBody;

        if (existingVaultId != null && !existingVaultId.isBlank()) {
            Map<String, Object> paypalSource = new HashMap<>();
            paypalSource.put("vault_id", existingVaultId);
            if (existingCustomerId != null && !existingCustomerId.isBlank()) {
                paypalSource.put("customer_id", existingCustomerId);
            }
            paypalSource.put("experience_context", experienceContext);
            requestBody = Map.of(
                    "intent", "CAPTURE",
                    "purchase_units", List.of(purchaseUnit),
                    "payment_source", Map.of("paypal", paypalSource)
            );
        } else if (requestVaulting) {
            Map<String, Object> attributes = Map.of(
                    "vault", Map.of(
                            "store_in_vault", "ON_SUCCESS",
                            "usage_type", "MERCHANT",
                            "customer_type", "CONSUMER"
                    )
            );
            requestBody = Map.of(
                    "intent", "CAPTURE",
                    "purchase_units", List.of(purchaseUnit),
                    "payment_source", Map.of("paypal", Map.of(
                            "attributes", attributes,
                            "experience_context", experienceContext
                    ))
            );
        } else {
            requestBody = Map.of(
                    "intent", "CAPTURE",
                    "purchase_units", List.of(purchaseUnit)
            );
        }

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
                String approveUrl = null;
                for (JsonNode link : node.path("links")) {
                    String rel = link.path("rel").asText();
                    if ("approve".equals(rel) || "payer-action".equals(rel)) {
                        approveUrl = link.path("href").asText();
                        break;
                    }
                }

                if (orderId.isBlank()) {
                    throw new RuntimeException("PayPal createOrder response missing id: " + responseBody);
                }

                // When an existing vault_id is reused and no payer action is
                // required, PayPal captures the order synchronously as part
                // of THIS response — status comes back "COMPLETED" with the
                // capture/vault details already present, rather than
                // "CREATED" awaiting a separate /capture call. Extract that
                // here so the caller never has to call captureOrder() on an
                // order PayPal has already finished.
                String status = node.path("status").asText(null);
                String captureId = null;
                String vaultId = null;
                String customerId = null;
                String payerEmail = null;
                String vaultStatus = null;

                if ("COMPLETED".equals(status)) {
                    JsonNode purchaseUnits = node.path("purchase_units");
                    if (purchaseUnits.isArray() && purchaseUnits.size() > 0) {
                        JsonNode captures = purchaseUnits.get(0).path("payments").path("captures");
                        if (captures.isArray() && captures.size() > 0) {
                            captureId = captures.get(0).path("id").asText(null);
                        }
                    }
                    JsonNode paypalSource = node.path("payment_source").path("paypal");
                    JsonNode vaultNode = paypalSource.path("attributes").path("vault");
                    vaultId = vaultNode.path("id").asText(null);
                    vaultStatus = vaultNode.path("status").asText(null);
                    customerId = vaultNode.path("customer").path("id").asText(null);
                    payerEmail = paypalSource.path("email_address").asText(null);
                }

                log.info("PayPal Order created: id={} status={} vaultReused={} approveRequired={} autoCaptured={}",
                        orderId, status, existingVaultId != null && !existingVaultId.isBlank(),
                        approveUrl != null, captureId != null);
                return new CreateOrderResult(orderId, approveUrl, status, captureId, vaultId,
                        customerId, payerEmail, vaultStatus);
            }
        } catch (Exception e) {
            throw new RuntimeException("PayPal createOrder failed: " + e.getMessage(), e);
        }
    }

    public CaptureResult captureOrder(String orderId) {
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

            JsonNode paypalSource = node.path("payment_source").path("paypal");
            JsonNode vaultNode = paypalSource.path("attributes").path("vault");
            String vaultId = vaultNode.path("id").asText(null);
            String vaultStatus = vaultNode.path("status").asText(null);
            String customerId = vaultNode.path("customer").path("id").asText(null);
            String payerEmail = paypalSource.path("email_address").asText(null);

            log.info("PayPal Order captured: orderId={} captureId={} vaultId={} vaultStatus={}",
                    orderId, captureId, vaultId, vaultStatus);
            return new CaptureResult(captureId, vaultId, customerId, payerEmail, vaultStatus);
        } catch (PaypalCaptureException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("PayPal captureOrder failed: " + e.getMessage(), e);
        }
    }

    /**
     * Fetches an existing Order's current state — the reconciliation
     * fallback when captureOrder() reports ORDER_ALREADY_CAPTURED (see
     * DepositService.confirmPaypalDepositInternal). Rather than giving up
     * and leaving the local transaction stuck PENDING, this looks up what
     * PayPal already captured (typically via vault-reuse auto-capture at
     * order-creation time, or a race with the webhook) so the caller can
     * credit the wallet against the real capture.
     */
    public CaptureResult getOrder(String orderId) {
        String token = getAccessToken();

        Request request = new Request.Builder()
                .url(baseUrl() + "/v2/checkout/orders/" + orderId)
                .addHeader("Authorization", "Bearer " + token)
                .get()
                .build();

        try (Response response = http.newCall(request).execute()) {
            String responseBody = response.body().string();
            JsonNode node = objectMapper.readTree(responseBody);

            if (!response.isSuccessful()) {
                throw new RuntimeException("PayPal getOrder failed (" + response.code() + "): " + responseBody);
            }

            JsonNode purchaseUnits = node.path("purchase_units");
            JsonNode captures = (purchaseUnits.isArray() && purchaseUnits.size() > 0)
                    ? purchaseUnits.get(0).path("payments").path("captures")
                    : null;

            if (captures == null || !captures.isArray() || captures.isEmpty()) {
                throw new RuntimeException("PayPal getOrder: order " + orderId + " has no captures yet: " + responseBody);
            }

            String captureId = captures.get(0).path("id").asText(null);

            JsonNode paypalSource = node.path("payment_source").path("paypal");
            JsonNode vaultNode = paypalSource.path("attributes").path("vault");
            String vaultId = vaultNode.path("id").asText(null);
            String vaultStatus = vaultNode.path("status").asText(null);
            String customerId = vaultNode.path("customer").path("id").asText(null);
            String payerEmail = paypalSource.path("email_address").asText(null);

            log.info("PayPal Order fetched for reconciliation: orderId={} captureId={} vaultStatus={}",
                    orderId, captureId, vaultStatus);
            return new CaptureResult(captureId, vaultId, customerId, payerEmail, vaultStatus);
        } catch (Exception e) {
            throw new RuntimeException("PayPal getOrder failed: " + e.getMessage(), e);
        }
    }

    // ─── Disbursement (Payouts API) ───────────────────────────────────────────

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