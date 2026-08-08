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
 * Flutterwave v4 API — OAuth2.0 client-credentials auth, the "General Flow"
 * for charges (customer → payment_method → charge), and the
 * recipient-then-transfer flow for disbursements.
 *
 * v4 is a full architectural replacement of v3: static secret-key auth is
 * gone (OAuth2 access tokens, 10-minute lifetime, cached/refreshed here —
 * see getAccessToken()); the base URL differs per environment (see
 * FlutterwaveConfig.baseUrl()); and webhook signatures are now an
 * HMAC-SHA256 of the raw body (see verifyWebhookSignature) sent in a
 * "flutterwave-signature" header — NOT the old plain-string "verif-hash"
 * comparison v3 used.
 *
 * SCOPE NOTE: initiateMobileMoneyCharge below only implements the
 * "mobile_money" payment_method type. Card payments are deliberately NOT
 * implemented here — v4 requires the CLIENT (frontend) to AES-256-encrypt
 * the raw card number/expiry/CVV with a nonce before it ever reaches your
 * server (see https://developer.flutterwave.com/docs/encryption). A
 * backend-only "give me a hosted checkout link" call the way v3 offered no
 * longer exists for cards — that needs a frontend change first, not just
 * this service. Bank transfer / USSD payment_method types also aren't
 * wired up yet; their exact request shapes weren't verified against docs
 * for this pass — check https://developer.flutterwave.com/docs/pay-with-bank-transfer
 * and https://developer.flutterwave.com/docs/ussd before adding them.
 *
 * See https://developer.flutterwave.com/docs
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlutterwaveService {

    private final FlutterwaveConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient http = new OkHttpClient();

    // ─── OAuth2 token management ──────────────────────────────────────────────

    private final ReentrantLock tokenLock = new ReentrantLock();
    private volatile String cachedAccessToken;
    private volatile long tokenExpiresAtEpochMillis = 0L;

    /** Tokens are valid 10 minutes (600s) — refresh 60s before actual expiry. */
    private static final long TOKEN_REFRESH_MARGIN_MILLIS = 60_000L;

    private String getAccessToken() {
        if (cachedAccessToken != null && System.currentTimeMillis() < tokenExpiresAtEpochMillis) {
            return cachedAccessToken;
        }

        tokenLock.lock();
        try {
            // Re-check after acquiring the lock — another thread may have refreshed already.
            if (cachedAccessToken != null && System.currentTimeMillis() < tokenExpiresAtEpochMillis) {
                return cachedAccessToken;
            }

            FormBody form = new FormBody.Builder()
                    .add("client_id", config.getClientId())
                    .add("client_secret", config.getClientSecret())
                    .add("grant_type", "client_credentials")
                    .build();

            Request request = new Request.Builder()
                    .url(config.oauthTokenUrl())
                    .post(form)
                    .build();

            try (Response response = http.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    throw new IllegalStateException(
                            "Flutterwave OAuth token request failed: HTTP " + response.code() + " " + responseBody);
                }

                JsonNode node = objectMapper.readTree(responseBody);
                String accessToken = node.path("access_token").asText(null);
                long expiresInSeconds = node.path("expires_in").asLong(600);

                if (accessToken == null || accessToken.isBlank()) {
                    throw new IllegalStateException("Flutterwave OAuth response missing access_token: " + responseBody);
                }

                cachedAccessToken = accessToken;
                tokenExpiresAtEpochMillis = System.currentTimeMillis()
                        + (expiresInSeconds * 1000) - TOKEN_REFRESH_MARGIN_MILLIS;

                log.info("Flutterwave OAuth token refreshed — expires in {}s", expiresInSeconds);
                return cachedAccessToken;
            }
        } catch (Exception e) {
            log.error("Flutterwave OAuth token refresh failed", e);
            throw new IllegalStateException("Failed to obtain Flutterwave access token: " + e.getMessage(), e);
        } finally {
            tokenLock.unlock();
        }
    }

    // ─── Deposits — mobile money charge (General Flow) ───────────────────────

    public record CheckoutResult(boolean success, String chargeId, String reference,
                                  String nextActionType, String redirectUrl,
                                  String paymentInstructionNote, String message) {}

    /**
     * Runs v4's "General Flow" for a mobile-money deposit: create/reuse a
     * customer, create a mobile_money payment method, initiate the charge.
     * Returns whatever next_action Flutterwave responds with — typically
     * "redirect_url" (send the customer there to authorize) or
     * "payment_instruction" (show the note, e.g. "approve on your phone").
     *
     * Kenyan mobile money (M-Pesa) is NOT routed through here — see
     * DepositService.initiateMpesaDeposit for the direct Daraja
     * integration. countryCode/network are the mobile network's country
     * dialling code (e.g. "233" for Ghana) and Flutterwave's network code
     * for that corridor (e.g. "MTN", "AIRTEL") — confirm the exact network
     * codes for your target corridors against Flutterwave's Mobile Money
     * docs before going live with a new country.
     */
    public CheckoutResult initiateMobileMoneyCharge(BigDecimal amount, String currency, String reference,
                                                      String customerEmail, String customerName,
                                                      String countryCode, String network, String phoneNumber) {
        try {
            String customerId = createOrGetCustomer(customerEmail, customerName, countryCode, phoneNumber);

            Map<String, Object> mobileMoney = new HashMap<>();
            mobileMoney.put("country_code", countryCode);
            mobileMoney.put("network", network);
            mobileMoney.put("phone_number", phoneNumber);

            Map<String, Object> pmBody = new HashMap<>();
            pmBody.put("type", "mobile_money");
            pmBody.put("mobile_money", mobileMoney);

            String pmResponseBody = post("/payment-methods", pmBody, true);
            JsonNode pmNode = objectMapper.readTree(pmResponseBody);
            if (!"success".equals(pmNode.path("status").asText(""))) {
                String message = pmNode.path("message").asText("Failed to create payment method");
                log.warn("Flutterwave payment-method creation rejected: reference={} message={}", reference, message);
                return new CheckoutResult(false, null, reference, null, null, null, message);
            }
            String paymentMethodId = pmNode.path("data").path("id").asText(null);

            Map<String, Object> chargeBody = new HashMap<>();
            chargeBody.put("reference", reference);
            chargeBody.put("currency", currency.toUpperCase());
            chargeBody.put("customer_id", customerId);
            chargeBody.put("payment_method_id", paymentMethodId);
            chargeBody.put("amount", amount);
            if (config.getRedirectUrl() != null && !config.getRedirectUrl().isBlank()) {
                chargeBody.put("redirect_url", config.getRedirectUrl());
            }

            String chargeResponseBody = post("/charges", chargeBody, true);
            log.info("Flutterwave charge response: {}", chargeResponseBody);
            JsonNode chargeNode = objectMapper.readTree(chargeResponseBody);

            String envelopeStatus = chargeNode.path("status").asText("");
            if (!"success".equals(envelopeStatus) && !"pending".equals(envelopeStatus)) {
                String message = chargeNode.path("message").asText("Unknown Flutterwave error");
                log.warn("Flutterwave charge rejected: reference={} message={}", reference, message);
                return new CheckoutResult(false, null, reference, null, null, null, message);
            }

            JsonNode data = chargeNode.path("data");
            String chargeId = data.path("id").asText(null);
            JsonNode nextAction = data.path("next_action");
            String nextActionType = nextAction.path("type").asText(null);
            String redirectUrl = nextAction.path("redirect_url").path("url").asText(null);
            String instructionNote = nextAction.path("payment_instruction").path("note").asText(null);

            log.info("Flutterwave charge created: reference={} chargeId={} nextAction={}",
                    reference, chargeId, nextActionType);
            return new CheckoutResult(true, chargeId, reference, nextActionType, redirectUrl,
                    instructionNote, "Charge created");
        } catch (Exception e) {
            log.error("Flutterwave charge initiation failed: reference={}", reference, e);
            return new CheckoutResult(false, null, reference, null, null, null,
                    "Flutterwave charge initiation failed: " + e.getMessage());
        }
    }

    private String createOrGetCustomer(String email, String name, String countryCode, String phoneNumber) throws Exception {
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
            Map<String, Object> phoneMap = new HashMap<>();
            phoneMap.put("country_code", countryCode);
            phoneMap.put("number", phoneNumber);
            body.put("phone", phoneMap);
        }

        String responseBody = post("/customers", body, true);
        JsonNode node = objectMapper.readTree(responseBody);
        if (!"success".equals(node.path("status").asText(""))) {
            throw new IllegalStateException("Failed to create Flutterwave customer: " + responseBody);
        }
        return node.path("data").path("id").asText(null);
    }

    // ─── Verify a charge ──────────────────────────────────────────────────────

    public record VerifyResult(boolean success, String status, String chargeId, String reference,
                                BigDecimal amount, String currency, String customerEmail, String message) {}

    /**
     * Verifies a charge by Flutterwave's own charge id (chg_xxx) — v4 only
     * exposes GET /charges/{id}; there is no verify-by-our-own-reference
     * lookup the way v3's verify_by_reference worked. Callers must resolve
     * the chargeId themselves first — DepositService now stores it as
     * Transaction.providerReference at initiation time (see
     * initiateFlutterwaveDeposit below).
     */
    public VerifyResult verifyChargeById(String chargeId) {
        try {
            String responseBody = get("/charges/" + chargeId);
            log.info("Flutterwave charge verify response: {}", responseBody);
            JsonNode node = objectMapper.readTree(responseBody);

            if (!"success".equals(node.path("status").asText(""))) {
                String message = node.path("message").asText("Unknown Flutterwave error");
                return new VerifyResult(false, null, chargeId, null, null, null, null, message);
            }

            JsonNode data = node.path("data");
            String status = data.path("status").asText(""); // "succeeded" | "failed" | "pending"
            String reference = data.path("reference").asText(null);
            BigDecimal amount = data.path("amount").isMissingNode() || data.path("amount").isNull()
                    ? null : data.path("amount").decimalValue();
            String currency = data.path("currency").asText(null);
            String customerEmail = data.path("customer").path("email").asText(null);

            boolean success = "succeeded".equalsIgnoreCase(status);
            return new VerifyResult(success, status, chargeId, reference, amount, currency, customerEmail,
                    "Verification " + (success ? "succeeded" : "returned status=" + status));
        } catch (Exception e) {
            log.error("Flutterwave charge verification failed: chargeId={}", chargeId, e);
            return new VerifyResult(false, null, chargeId, null, null, null, null,
                    "Flutterwave verification failed: " + e.getMessage());
        }
    }

    // ─── Transfers (disbursements) ────────────────────────────────────────────
    public record TransferResult(boolean success, String message, String transferId, String reference) {}

    /**
     * v4 Transfers need a recipient object created first, then a transfer
     * referencing its recipient_id inside a payment_instruction — unlike
     * v3's single flat call. See createTransferRecipient's javadoc for an
     * open question on the exact recipient "type" string for your
     * corridors — confirm before production use.
     *
     * accountBank/accountNumber keep the same meaning as before: bank code
     * + account number for bank transfers, or mobile network code + phone
     * number for mobile money transfers.
     *
     * Optional recipientId parameter — if provided (cached from a previous
     * attempt), reuses that recipient instead of creating a duplicate.
     * This prevents orphaned recipients if transfer initiation fails after
     * recipient creation succeeds.
     *
     * NOTE: transfers are reconciled solely via the account-wide webhook
     * registered in the Flutterwave Dashboard ("transfer.disburse" event,
     * see PaymentCallbackController.flutterwaveWebhook) — same pattern as
     * PayPal disbursements. There is no per-transfer callback_url field
     * sent in the request body here; that would just be a redundant
     * second notification for the same event.
     */
    public TransferResult initiateTransfer(String accountBank, String accountNumber, BigDecimal amount,
                                            String currency, String reference, String narration,
                                            String beneficiaryName) {
        return initiateTransfer(accountBank, accountNumber, amount, currency, reference, narration, beneficiaryName, null);
    }

    public TransferResult initiateTransfer(String accountBank, String accountNumber, BigDecimal amount,
                                            String currency, String reference, String narration,
                                            String beneficiaryName, String cachedRecipientId) {
        if (amount.compareTo(config.getTransfer().getMinAmount()) < 0
                || amount.compareTo(config.getTransfer().getMaxAmount()) > 0) {
            return new TransferResult(false,
                    "Amount must be between " + config.getTransfer().getMinAmount()
                            + " and " + config.getTransfer().getMaxAmount() + " " + currency,
                    null, reference);
        }

        String recipientId;
        if (cachedRecipientId != null && !cachedRecipientId.isBlank()) {
            // Reuse a previously-created recipient (retry case).
            // This prevents orphaned recipients if transfer initiation failed
            // on a prior attempt after recipient creation succeeded.
            recipientId = cachedRecipientId;
            log.info("Flutterwave transfer initiateTransfer: reusing cached recipientId={} for reference={}", recipientId, reference);
        } else {
            // Create a new recipient.
            try {
                recipientId = createTransferRecipient(accountBank, accountNumber, currency, beneficiaryName);
            } catch (Exception e) {
                log.error("Flutterwave recipient creation threw before a response could be parsed: reference={}",
                        reference, e);
                throw new FlutterwaveTransferException(reference, "RECIPIENT_CREATION_FAILED",
                        "Flutterwave recipient creation failed: " + e.getMessage());
            }
        }

        if (recipientId == null) {
            return new TransferResult(false, "Failed to create Flutterwave transfer recipient", null, reference);
        }

        Map<String, Object> amountMap = new HashMap<>();
        amountMap.put("applies_to", "destination_currency");
        amountMap.put("value", amount);

        Map<String, Object> paymentInstruction = new HashMap<>();
        paymentInstruction.put("source_currency", currency.toUpperCase());
        paymentInstruction.put("destination_currency", currency.toUpperCase());
        paymentInstruction.put("amount", amountMap);
        paymentInstruction.put("recipient_id", recipientId);

        Map<String, Object> body = new HashMap<>();
        body.put("action", "instant");
        body.put("reference", reference);
        body.put("narration", narration != null ? narration : "Premisave wallet disbursement");
        body.put("payment_instruction", paymentInstruction);

        try {
            String responseBody = post("/transfers", body, true);
            log.info("Flutterwave transfer response: {}", responseBody);
            JsonNode node = objectMapper.readTree(responseBody);

            String status = node.path("status").asText("");
            if (!"success".equals(status)) {
                String message = node.path("message").asText("Unknown Flutterwave transfer error");
                log.warn("Flutterwave transfer rejected: reference={} message={}", reference, message);
                return new TransferResult(false, message, null, reference);
            }

            JsonNode data = node.path("data");
            String transferId = data.path("id").asText(null);
            String transferStatus = data.path("status").asText("NEW"); // NEW | PENDING | SUCCESSFUL | FAILED

            log.info("Flutterwave transfer initiated: reference={} transferId={} status={}",
                    reference, transferId, transferStatus);
            return new TransferResult(true, "Transfer " + transferStatus.toLowerCase(), transferId, reference);
        } catch (Exception e) {
            log.error("Flutterwave transfer initiation threw before a response could be parsed: reference={}",
                    reference, e);
            throw new FlutterwaveTransferException(reference, "INITIATION_FAILED",
                    "Flutterwave transfer initiation failed: " + e.getMessage());
        }
    }

    /**
     * Creates a Flutterwave recipient before initiating a transfer.
     *
     * RECIPIENT TYPE SELECTION: v4 recipient "type" values are
     * corridor-specific. The implementation currently derives it as
     * "bank_" + currency (e.g., "bank_usd", "bank_kes"), which is a
     * best-effort guess NOT confirmed for all corridors.
     *
     * BEFORE PRODUCTION: Confirm the exact recipient "type" values with
     * Flutterwave's documentation and your account manager for:
     *  - Bank transfers in KES (if supported)
     *  - Mobile-money transfers to your target countries
     *
     * See https://developer.flutterwave.com/docs/bank-transfer and
     * https://developer.flutterwave.com/docs/mobile-money-1 for
     * the authoritative per-corridor type strings. Examples from
     * Flutterwave's docs: "bank_ngn" (Nigerian banks), "mobile_money_ke"
     * (Kenyan mobile money), "mobile_money_ug" (Ugandan mobile money).
     *
     * Update createTransferRecipient and processFlutterwaveDisbursement
     * hard-code the correct types once confirmed — do NOT rely on this
     * currency-based derivation in production without verification.
     */
    public String createTransferRecipient(String accountBank, String accountNumber, String currency,
                                            String beneficiaryName) throws Exception {
        // TODO: CONFIRM recipient type values with Flutterwave for production corridors
        // Current implementation: "bank_" + currency (e.g., "bank_usd", "bank_kes")
        // This is NOT confirmed. See javadoc above for action items before going live.
        String recipientType = "bank_" + currency.toLowerCase();

        Map<String, Object> bank = new HashMap<>();
        bank.put("account_number", accountNumber);
        bank.put("code", accountBank);

        Map<String, Object> body = new HashMap<>();
        body.put("type", recipientType);
        body.put("bank", bank);
        if (beneficiaryName != null && !beneficiaryName.isBlank()) {
            String[] parts = beneficiaryName.trim().split("\\s+", 2);
            Map<String, Object> nameMap = new HashMap<>();
            nameMap.put("first", parts[0]);
            if (parts.length > 1) nameMap.put("last", parts[1]);
            body.put("name", nameMap);
        }

        String responseBody = post("/transfers/recipients", body, true);
        log.info("Flutterwave recipient response: {}", responseBody);
        JsonNode node = objectMapper.readTree(responseBody);
        if (!"success".equals(node.path("status").asText(""))) {
            log.warn("Flutterwave recipient creation rejected: recipientType={} message={}", 
                    recipientType, node.path("message").asText(""));
            return null;
        }
        return node.path("data").path("id").asText(null);
    }

    /** Manual reconciliation query — same use case as MpesaService.queryTransactionStatus. */
    public TransferResult getTransferStatus(String transferId) {
        try {
            String responseBody = get("/transfers/" + transferId);
            log.info("Flutterwave transfer status response: {}", responseBody);
            JsonNode node = objectMapper.readTree(responseBody);

            if (!"success".equals(node.path("status").asText(""))) {
                return new TransferResult(false, node.path("message").asText("Unknown error"), transferId, null);
            }

            JsonNode data = node.path("data");
            String transferStatus = data.path("status").asText("");
            String reference = data.path("reference").asText(null);

            boolean terminalSuccess = "SUCCESSFUL".equalsIgnoreCase(transferStatus);
            return new TransferResult(terminalSuccess, "Transfer status: " + transferStatus, transferId, reference);
        } catch (Exception e) {
            log.error("Flutterwave transfer status query failed: transferId={}", transferId, e);
            return new TransferResult(false, "Query failed: " + e.getMessage(), transferId, null);
        }
    }

    // ─── Webhook signature verification ──────────────────────────────────────

    /**
     * v4 webhook signatures are an HMAC-SHA256 of the RAW request body,
     * keyed with your webhook-secret-hash, base64-encoded, sent in the
     * "flutterwave-signature" header — NOT the old v3 plain shared-secret
     * comparison against "verif-hash". rawBody must be the exact bytes
     * Flutterwave sent (PaymentCallbackController already reads the body
     * as a raw String for this reason — don't re-serialize a parsed object,
     * that will produce a different byte sequence and always fail).
     */
    public boolean verifyWebhookSignature(String rawBody, String receivedSignature) {
        if (config.getWebhookSecretHash() == null || config.getWebhookSecretHash().isBlank()) {
            log.error("Flutterwave webhook verification skipped — flutterwave.webhook-secret-hash is not " +
                    "configured. Rejecting webhook as unverifiable.");
            return false;
        }
        if (receivedSignature == null || rawBody == null) {
            log.warn("Flutterwave webhook rejected — missing flutterwave-signature header or body");
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
            log.error("Flutterwave webhook signature computation failed", e);
            return false;
        }
    }

    // ─── Account Linking (Wallet Management) ──────────────────────────────────

    /**
     * Links a Flutterwave account to the wallet by storing the customer credentials.
     * Called after the user approves linking on Flutterwave's side.
     */
    public void linkFlutterwaveAccount(com.premisave.wallet.entity.Wallet wallet, String customerId,
                                        String paymentMethodId, String network, String phone) {
        wallet.setFlutterwaveCustomerId(customerId);
        wallet.setFlutterwavePaymentMethodId(paymentMethodId);
        wallet.setFlutterwavePaymentMethodNetwork(network);
        wallet.setFlutterwavePaymentMethodPhone(phone);
        wallet.setPendingFlutterwaveSetupTokenId(null);
        // Note: caller (WalletController) is responsible for saving via walletRepository
        log.info("Flutterwave account linked in memory: customerId={}", customerId);
    }

    /**
     * Unlinks a Flutterwave account from the wallet.
     * Called when user explicitly disconnects their account.
     */
    public void unlinkAccount(com.premisave.wallet.entity.Wallet wallet) {
        wallet.setFlutterwaveCustomerId(null);
        wallet.setFlutterwavePaymentMethodId(null);
        wallet.setFlutterwavePaymentMethodNetwork(null);
        wallet.setFlutterwavePaymentMethodPhone(null);
        wallet.setPendingFlutterwaveSetupTokenId(null);
        // Note: caller (WalletController) is responsible for saving via walletRepository
        log.info("Flutterwave account unlinked from wallet");
    }

    // ─── HTTP helpers ─────────────────────────────────────────────────────────

    private String post(String path, Map<String, Object> body, boolean authenticated) throws Exception {
        String json = objectMapper.writeValueAsString(body);
        RequestBody rb = RequestBody.create(json, MediaType.parse("application/json"));
        Request.Builder builder = new Request.Builder()
                .url(config.baseUrl() + path)
                .addHeader("Content-Type", "application/json")
                .addHeader("X-Trace-Id", UUID.randomUUID().toString())
                .addHeader("X-Idempotency-Key", UUID.randomUUID().toString())
                .post(rb);
        if (authenticated) {
            builder.addHeader("Authorization", "Bearer " + getAccessToken());
        }
        try (Response response = http.newCall(builder.build()).execute()) {
            return response.body() != null ? response.body().string() : "";
        }
    }

    private String get(String path) throws Exception {
        Request request = new Request.Builder()
                .url(config.baseUrl() + path)
                .addHeader("Authorization", "Bearer " + getAccessToken())
                .addHeader("X-Trace-Id", UUID.randomUUID().toString())
                .get()
                .build();
        try (Response response = http.newCall(request).execute()) {
            return response.body() != null ? response.body().string() : "";
        }
    }
}