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
 * PayPal v2 Orders API (deposits, with Vault support) + Vault v3 API
 * (standalone account linking, no payment) + Payouts API (disbursements).
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

    // ─── Standalone Account Linking (Vault v3 — no payment) ───────────────────

    /** Result of createSetupToken — approveUrl is where the payer approves the link. */
    public record SetupTokenResult(String setupTokenId, String approveUrl) {}

    /** Result of createPaymentTokenFromSetupToken — the linked account's vault_id/customer/email. */
    public record LinkAccountResult(String vaultId, String customerId, String payerEmail) {}

    /**
     * Creates a PayPal Vault v3 setup token — the "link an account without
     * paying" analogue of Stripe's SetupIntent (see
     * DepositService.createStripeSetupIntent). customerId is set explicitly
     * to our own userId so createPaymentTokenFromSetupToken's response can
     * be checked against the caller at confirm time (see
     * DepositService.confirmPaypalLink) — without that, a setupTokenId
     * minted for one user could otherwise be replayed to link a different
     * user's PayPal account.
     */
    public SetupTokenResult createSetupToken(String customerId) {
        String token = getAccessToken();

        Map<String, Object> requestBody = Map.of(
                "payment_source", Map.of(
                        "paypal", Map.of(
                                "usage_type", "MERCHANT",
                                "customer_type", "CONSUMER",
                                "experience_context", Map.of(
                                        "return_url", returnUrl,
                                        "cancel_url", cancelUrl
                                )
                        )
                ),
                "customer", Map.of("id", customerId)
        );

        try {
            String json = objectMapper.writeValueAsString(requestBody);
            RequestBody rb = RequestBody.create(json, MediaType.parse("application/json"));

            Request request = new Request.Builder()
                    .url(baseUrl() + "/v3/vault/setup-tokens")
                    .addHeader("Authorization", "Bearer " + token)
                    .addHeader("PayPal-Request-Id", UUID.randomUUID().toString())
                    .post(rb)
                    .build();

            try (Response response = http.newCall(request).execute()) {
                String responseBody = response.body().string();
                JsonNode node = objectMapper.readTree(responseBody);

                if (!response.isSuccessful()) {
                    throw new RuntimeException("PayPal createSetupToken failed (" + response.code() + "): " + responseBody);
                }

                String setupTokenId = node.path("id").asText();
                String approveUrl = null;
                for (JsonNode link : node.path("links")) {
                    if ("approve".equals(link.path("rel").asText())) {
                        approveUrl = link.path("href").asText();
                        break;
                    }
                }

                if (setupTokenId.isBlank() || approveUrl == null) {
                    throw new RuntimeException("PayPal createSetupToken response missing id/approve link: " + responseBody);
                }

                log.info("PayPal setup token created: id={} customerId={}", setupTokenId, customerId);
                return new SetupTokenResult(setupTokenId, approveUrl);
            }
        } catch (Exception e) {
            throw new RuntimeException("PayPal createSetupToken failed: " + e.getMessage(), e);
        }
    }

    /**
     * Exchanges an approved setup token for a payment token — the actual
     * vault_id that can be reused in future createOrder() calls (see
     * DepositService.initiatePaypalDeposit's existingVaultId param). Call
     * this after the payer returns from approving the setup token's
     * approveUrl.
     */
    public LinkAccountResult createPaymentTokenFromSetupToken(String setupTokenId) {
        String token = getAccessToken();

        Map<String, Object> requestBody = Map.of(
                "payment_source", Map.of(
                        "token", Map.of(
                                "id", setupTokenId,
                                "type", "SETUP_TOKEN"
                        )
                )
        );

        try {
            String json = objectMapper.writeValueAsString(requestBody);
            RequestBody rb = RequestBody.create(json, MediaType.parse("application/json"));

            Request request = new Request.Builder()
                    .url(baseUrl() + "/v3/vault/payment-tokens")
                    .addHeader("Authorization", "Bearer " + token)
                    .addHeader("PayPal-Request-Id", UUID.randomUUID().toString())
                    .post(rb)
                    .build();

            try (Response response = http.newCall(request).execute()) {
                String responseBody = response.body().string();
                JsonNode node = objectMapper.readTree(responseBody);

                if (!response.isSuccessful()) {
                    throw new RuntimeException("PayPal createPaymentToken failed (" + response.code() + "): " + responseBody);
                }

                String vaultId = node.path("id").asText(null);
                String customerId = node.path("customer").path("id").asText(null);
                String payerEmail = node.path("payment_source").path("paypal").path("email_address").asText(null);

                if (vaultId == null || vaultId.isBlank()) {
                    throw new RuntimeException("PayPal createPaymentToken response missing id: " + responseBody);
                }

                log.info("PayPal payment token created: vaultId={} customerId={} email={}",
                        vaultId, customerId, payerEmail);
                return new LinkAccountResult(vaultId, customerId, payerEmail);
            }
        } catch (Exception e) {
            throw new RuntimeException("PayPal createPaymentToken failed: " + e.getMessage(), e);
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