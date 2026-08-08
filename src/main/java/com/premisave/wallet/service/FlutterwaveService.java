package com.premisave.wallet.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.premisave.wallet.config.FlutterwaveConfig;
import com.premisave.wallet.exception.FlutterwaveTransferException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Flutterwave v4 service.
 *
 * Sandbox base URL : https://developersandbox-api.flutterwave.com
 * Production base URL: https://api.flutterwave.com
 *
 * FlutterwaveConfig.baseUrl() must return the correct URL for the environment.
 *
 * OAuth2 token endpoint (both environments):
 *   https://idp.flutterwave.com/realms/flutterwave/protocol/openid-connect/token
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlutterwaveService {

    // Correct OAuth2 token URL from official v4 docs
    private static final String OAUTH_TOKEN_URL =
            "https://idp.flutterwave.com/realms/flutterwave/protocol/openid-connect/token";

    // Token lifetime is 600s (10 min). Refresh 60s before expiry.
    private static final long TOKEN_REFRESH_MARGIN_MILLIS = 60_000L;

    private final FlutterwaveConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient http = new OkHttpClient();

    private final ReentrantLock tokenLock = new ReentrantLock();
    private volatile String cachedAccessToken;
    private volatile long tokenExpiresAtEpochMillis = 0L;

    // ─── OAuth2 token management ──────────────────────────────────────────────

    private String getAccessToken() {
        if (cachedAccessToken != null && System.currentTimeMillis() < tokenExpiresAtEpochMillis) {
            return cachedAccessToken;
        }

        tokenLock.lock();
        try {
            if (cachedAccessToken != null && System.currentTimeMillis() < tokenExpiresAtEpochMillis) {
                return cachedAccessToken;
            }

            FormBody form = new FormBody.Builder()
                    .add("client_id", config.getClientId())
                    .add("client_secret", config.getClientSecret())
                    .add("grant_type", "client_credentials")
                    .build();

            Request request = new Request.Builder()
                    .url(OAUTH_TOKEN_URL)
                    .post(form)
                    .build();

            try (Response response = http.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    throw new IllegalStateException(
                            "Flutterwave OAuth failed: HTTP " + response.code() + " " + body);
                }

                JsonNode node = objectMapper.readTree(body);
                String accessToken = node.path("access_token").asText(null);
                long expiresIn = node.path("expires_in").asLong(600);

                if (accessToken == null || accessToken.isBlank()) {
                    throw new IllegalStateException("Flutterwave OAuth missing access_token: " + body);
                }

                cachedAccessToken = accessToken;
                tokenExpiresAtEpochMillis = System.currentTimeMillis()
                        + (expiresIn * 1000) - TOKEN_REFRESH_MARGIN_MILLIS;

                log.info("Flutterwave OAuth token refreshed — expires in {}s", expiresIn);
                return cachedAccessToken;
            }
        } catch (Exception e) {
            log.error("Flutterwave OAuth token refresh failed", e);
            throw new IllegalStateException("Failed to obtain Flutterwave access token: " + e.getMessage(), e);
        } finally {
            tokenLock.unlock();
        }
    }

    // ─── Deposits — mobile money charge ──────────────────────────────────────

    public record CheckoutResult(boolean success, String chargeId, String reference,
                                  String nextActionType, String redirectUrl,
                                  String paymentInstructionNote, String message) {}

    /**
     * Flutterwave v4 General Flow for mobile money deposit:
     *
     * Step 1: POST /customers            — create/get customer
     * Step 2: POST /payment-methods      — create mobile_money payment method
     * Step 3: POST /charges              — initiate charge
     *
     * next_action.type can be:
     *   "payment_instruction"  — show the note, user approves on phone
     *   "redirect_url"         — redirect user to URL returned
     *
     * countryCode: dialling code e.g. "233" for Ghana
     * network: e.g. "MTN", "airtel", "Mpesa" — case as per Flutterwave docs
     */
    public CheckoutResult initiateMobileMoneyCharge(BigDecimal amount, String currency, String reference,
                                                     String customerEmail, String customerName,
                                                     String countryCode, String network,
                                                     String phoneNumber) {
        try {
            String customerId = createOrGetCustomer(customerEmail, customerName, countryCode, phoneNumber);

            // Step 2: Create mobile_money payment method
            Map<String, Object> mobileMoney = new HashMap<>();
            mobileMoney.put("country_code", countryCode);
            mobileMoney.put("network", network);
            mobileMoney.put("phone_number", phoneNumber);

            Map<String, Object> pmBody = new HashMap<>();
            pmBody.put("type", "mobile_money");
            pmBody.put("mobile_money", mobileMoney);

            String pmResponse = post("/payment-methods", pmBody);
            JsonNode pmNode = objectMapper.readTree(pmResponse);

            if (!"success".equals(pmNode.path("status").asText(""))) {
                String msg = pmNode.path("message").asText("Failed to create payment method");
                log.warn("Flutterwave payment-method creation failed: reference={} message={}", reference, msg);
                return new CheckoutResult(false, null, reference, null, null, null, msg);
            }

            String paymentMethodId = pmNode.path("data").path("id").asText(null);

            // Step 3: Create charge
            Map<String, Object> chargeBody = new HashMap<>();
            chargeBody.put("reference", reference);
            chargeBody.put("currency", currency.toUpperCase());
            chargeBody.put("customer_id", customerId);
            chargeBody.put("payment_method_id", paymentMethodId);
            chargeBody.put("amount", amount);
            if (config.getRedirectUrl() != null && !config.getRedirectUrl().isBlank()) {
                chargeBody.put("redirect_url", config.getRedirectUrl());
            }

            String chargeResponse = post("/charges", chargeBody);
            log.info("Flutterwave charge response: reference={} body={}", reference, chargeResponse);
            JsonNode chargeNode = objectMapper.readTree(chargeResponse);

            String envelopeStatus = chargeNode.path("status").asText("");
            if (!"success".equals(envelopeStatus) && !"pending".equals(envelopeStatus)) {
                String msg = chargeNode.path("message").asText("Unknown Flutterwave error");
                log.warn("Flutterwave charge rejected: reference={} message={}", reference, msg);
                return new CheckoutResult(false, null, reference, null, null, null, msg);
            }

            JsonNode data = chargeNode.path("data");
            String chargeId = data.path("id").asText(null);
            JsonNode nextAction = data.path("next_action");
            String nextActionType = nextAction.path("type").asText(null);

            // "redirect_url" next_action
            String redirectUrl = null;
            if ("redirect_url".equals(nextActionType)) {
                redirectUrl = nextAction.path("redirect_url").path("url").asText(null);
            }

            // "payment_instruction" next_action
            String instructionNote = null;
            if ("payment_instruction".equals(nextActionType)) {
                instructionNote = nextAction.path("payment_instruction").path("note").asText(null);
            }

            log.info("Flutterwave charge created: reference={} chargeId={} nextAction={}",
                    reference, chargeId, nextActionType);

            return new CheckoutResult(true, chargeId, reference, nextActionType,
                    redirectUrl, instructionNote, "Charge created");

        } catch (Exception e) {
            log.error("Flutterwave charge initiation failed: reference={}", reference, e);
            return new CheckoutResult(false, null, reference, null, null, null,
                    "Charge initiation failed: " + e.getMessage());
        }
    }

    /**
     * Creates a Flutterwave customer.
     * Only email is required; name and phone improve customer profiling.
     */
    private String createOrGetCustomer(String email, String name, String countryCode,
                                        String phoneNumber) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("email", email);

        if (name != null && !name.isBlank()) {
            String[] parts = name.trim().split("\\s+", 2);
            Map<String, Object> nameMap = new HashMap<>();
            nameMap.put("first", parts[0]);
            if (parts.length > 1) nameMap.put("last", parts[1]);
            body.put("name", nameMap);
        }

        if (phoneNumber != null && !phoneNumber.isBlank()) {
            Map<String, Object> phone = new HashMap<>();
            phone.put("country_code", countryCode);
            phone.put("number", phoneNumber);
            body.put("phone", phone);
        }

        String responseBody = post("/customers", body);
        JsonNode node = objectMapper.readTree(responseBody);

        if (!"success".equals(node.path("status").asText(""))) {
            throw new IllegalStateException("Failed to create Flutterwave customer: " + responseBody);
        }

        return node.path("data").path("id").asText(null);
    }

    // ─── Verify a charge ──────────────────────────────────────────────────────

    public record VerifyResult(boolean success, String status, String chargeId, String reference,
                                BigDecimal amount, String currency, String customerEmail,
                                String message) {}

    /**
     * Verifies a charge by Flutterwave's charge id (chg_xxx).
     * GET /charges/{id}
     *
     * status values from docs: "pending" | "succeeded" | "failed"
     */
    public VerifyResult verifyChargeById(String chargeId) {
        try {
            String responseBody = get("/charges/" + chargeId);
            log.info("Flutterwave verify charge: chargeId={} response={}", chargeId, responseBody);
            JsonNode node = objectMapper.readTree(responseBody);

            if (!"success".equals(node.path("status").asText(""))) {
                String msg = node.path("message").asText("Unknown error");
                return new VerifyResult(false, null, chargeId, null, null, null, null, msg);
            }

            JsonNode data = node.path("data");
            String status = data.path("status").asText("");
            String reference = data.path("reference").asText(null);
            BigDecimal amount = data.path("amount").isMissingNode() ? null
                    : data.path("amount").decimalValue();
            String currency = data.path("currency").asText(null);
            String customerEmail = data.path("customer").path("email").asText(null);

            boolean success = "succeeded".equalsIgnoreCase(status);
            return new VerifyResult(success, status, chargeId, reference, amount, currency,
                    customerEmail, "Charge status: " + status);

        } catch (Exception e) {
            log.error("Flutterwave charge verification failed: chargeId={}", chargeId, e);
            return new VerifyResult(false, null, chargeId, null, null, null, null,
                    "Verification failed: " + e.getMessage());
        }
    }

    // ─── Disbursements — direct transfer ─────────────────────────────────────

    public record TransferResult(boolean success, String message, String transferId, String reference) {}

    /**
     * Initiates a mobile money transfer using Flutterwave v4 direct transfer.
     *
     * Endpoint: POST /direct-transfers
     *
     * From the docs the correct structure is:
     * {
     *   "action": "instant",
     *   "type": "mobile_money",
     *   "reference": "...",
     *   "narration": "...",
     *   "payment_instruction": {
     *     "source_currency": "KES",
     *     "destination_currency": "KES",
     *     "amount": { "applies_to": "destination_currency", "value": 1000 },
     *     "recipient": {
     *       "name": { "first": "John", "last": "Doe" },
     *       "mobile_money": {
     *         "network": "Mpesa",
     *         "msisdn": "2547XXXXXXXX"    <- must include country code
     *       }
     *     }
     *   }
     * }
     *
     * Response status will be "NEW" — final status comes via webhook "transfer.disburse"
     * Webhook data.status: "SUCCESSFUL" | "FAILED"
     */
    public TransferResult initiateTransfer(String msisdn, String network,
                                            String sourceCurrency, String destinationCurrency,
                                            BigDecimal amount, String reference, String narration,
                                            String beneficiaryFirstName, String beneficiaryLastName) {

        if (amount.compareTo(config.getTransfer().getMinAmount()) < 0
                || amount.compareTo(config.getTransfer().getMaxAmount()) > 0) {
            return new TransferResult(false,
                    "Amount must be between " + config.getTransfer().getMinAmount()
                            + " and " + config.getTransfer().getMaxAmount() + " " + destinationCurrency,
                    null, reference);
        }

        try {
            Map<String, Object> amountMap = new HashMap<>();
            amountMap.put("applies_to", "destination_currency");
            amountMap.put("value", amount);

            Map<String, Object> mobileMoney = new HashMap<>();
            mobileMoney.put("network", network);
            mobileMoney.put("msisdn", msisdn);  // must include country code e.g. 2547XXXXXXXX

            Map<String, Object> nameMap = new HashMap<>();
            nameMap.put("first", beneficiaryFirstName != null ? beneficiaryFirstName : "");
            if (beneficiaryLastName != null && !beneficiaryLastName.isBlank()) {
                nameMap.put("last", beneficiaryLastName);
            }

            Map<String, Object> recipient = new HashMap<>();
            recipient.put("name", nameMap);
            recipient.put("mobile_money", mobileMoney);

            Map<String, Object> paymentInstruction = new HashMap<>();
            paymentInstruction.put("source_currency", sourceCurrency.toUpperCase());
            paymentInstruction.put("destination_currency", destinationCurrency.toUpperCase());
            paymentInstruction.put("amount", amountMap);
            paymentInstruction.put("recipient", recipient);

            Map<String, Object> body = new HashMap<>();
            body.put("action", "instant");
            body.put("type", "mobile_money");
            body.put("reference", reference);
            body.put("narration", narration != null ? narration : "Premisave wallet disbursement");
            body.put("payment_instruction", paymentInstruction);

            String responseBody = post("/direct-transfers", body);
            log.info("Flutterwave direct-transfer response: reference={} body={}", reference, responseBody);
            JsonNode node = objectMapper.readTree(responseBody);

            String envelopeStatus = node.path("status").asText("");
            if (!"success".equals(envelopeStatus)) {
                String msg = node.path("message").asText("Unknown transfer error");
                log.warn("Flutterwave transfer rejected: reference={} message={}", reference, msg);
                return new TransferResult(false, msg, null, reference);
            }

            JsonNode data = node.path("data");
            String transferId = data.path("id").asText(null);
            // Status is always "NEW" on creation — final status via webhook
            log.info("Flutterwave transfer initiated: reference={} transferId={}", reference, transferId);
            return new TransferResult(true, "Transfer initiated", transferId, reference);

        } catch (Exception e) {
            log.error("Flutterwave transfer failed: reference={}", reference, e);
            throw new FlutterwaveTransferException(reference, "INITIATION_FAILED",
                    "Flutterwave transfer initiation failed: " + e.getMessage());
        }
    }

    /**
     * Query the status of a transfer.
     * GET /transfers/{id}
     * Used for manual reconciliation.
     */
    public TransferResult getTransferStatus(String transferId) {
        try {
            String responseBody = get("/transfers/" + transferId);
            log.info("Flutterwave transfer status: transferId={} response={}", transferId, responseBody);
            JsonNode node = objectMapper.readTree(responseBody);

            if (!"success".equals(node.path("status").asText(""))) {
                return new TransferResult(false,
                        node.path("message").asText("Unknown error"), transferId, null);
            }

            JsonNode data = node.path("data");
            String status = data.path("status").asText("");
            String reference = data.path("reference").asText(null);

            boolean success = "SUCCESSFUL".equalsIgnoreCase(status);
            return new TransferResult(success, "Transfer status: " + status, transferId, reference);

        } catch (Exception e) {
            log.error("Flutterwave transfer status query failed: transferId={}", transferId, e);
            return new TransferResult(false, "Query failed: " + e.getMessage(), transferId, null);
        }
    }

    // ─── Account Linking ──────────────────────────────────────────────────────

    /**
     * Links a Flutterwave account to the wallet.
     * Caller must save the wallet after calling this.
     */
    public void linkFlutterwaveAccount(com.premisave.wallet.entity.Wallet wallet, String customerId,
                                        String paymentMethodId, String network, String phone) {
        wallet.setFlutterwaveCustomerId(customerId);
        wallet.setFlutterwavePaymentMethodId(paymentMethodId);
        wallet.setFlutterwavePaymentMethodNetwork(network);
        wallet.setFlutterwavePaymentMethodPhone(phone);
        wallet.setPendingFlutterwaveSetupTokenId(null);
        log.info("Flutterwave account linked: customerId={}", customerId);
    }

    /**
     * Unlinks a Flutterwave account from the wallet.
     * Caller must save the wallet after calling this.
     */
    public void unlinkAccount(com.premisave.wallet.entity.Wallet wallet) {
        wallet.setFlutterwaveCustomerId(null);
        wallet.setFlutterwavePaymentMethodId(null);
        wallet.setFlutterwavePaymentMethodNetwork(null);
        wallet.setFlutterwavePaymentMethodPhone(null);
        wallet.setPendingFlutterwaveSetupTokenId(null);
        log.info("Flutterwave account unlinked");
    }

    // ─── Webhook signature verification ──────────────────────────────────────

    /**
     * Verifies a Flutterwave v4 webhook signature.
     * The signature is HMAC-SHA256 of the raw body, base64-encoded,
     * sent in the "flutterwave-signature" header.
     */
    public boolean verifyWebhookSignature(String rawBody, String receivedSignature) {
        if (config.getWebhookSecretHash() == null || config.getWebhookSecretHash().isBlank()) {
            log.error("flutterwave.webhook-secret-hash is not configured — rejecting webhook");
            return false;
        }
        if (receivedSignature == null || rawBody == null) {
            log.warn("Flutterwave webhook rejected — missing signature or body");
            return false;
        }
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(
                    config.getWebhookSecretHash().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] computed = hmac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            String computedSignature = Base64.getEncoder().encodeToString(computed);
            return computedSignature.equals(receivedSignature);
        } catch (Exception e) {
            log.error("Flutterwave webhook signature verification failed", e);
            return false;
        }
    }

    // ─── HTTP helpers ─────────────────────────────────────────────────────────

    private String post(String path, Map<String, Object> body) throws Exception {
        String json = objectMapper.writeValueAsString(body);
        RequestBody rb = RequestBody.create(json, MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(config.baseUrl() + path)
                .addHeader("Authorization", "Bearer " + getAccessToken())
                .addHeader("Content-Type", "application/json")
                .addHeader("X-Trace-Id", UUID.randomUUID().toString())
                .addHeader("X-Idempotency-Key", UUID.randomUUID().toString())
                .post(rb)
                .build();
        try (Response response = http.newCall(request).execute()) {
            return response.body() != null ? response.body().string() : "";
        }
    }

    private String get(String path) throws Exception {
        Request request = new Request.Builder()
                .url(config.baseUrl() + path)
                .addHeader("Authorization", "Bearer " + getAccessToken())
                .addHeader("Content-Type", "application/json")
                .addHeader("X-Trace-Id", UUID.randomUUID().toString())
                .get()
                .build();
        try (Response response = http.newCall(request).execute()) {
            return response.body() != null ? response.body().string() : "";
        }
    }
}