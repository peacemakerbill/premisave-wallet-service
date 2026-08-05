package com.premisave.wallet.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.premisave.wallet.config.FlutterwaveConfig;
import com.premisave.wallet.exception.FlutterwaveTransferException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Flutterwave v3 REST API — Standard/Hosted Checkout (deposits: card, mobile
 * money, bank transfer, USSD all through one endpoint — Flutterwave shows
 * whichever payment methods are enabled on its hosted page) and the
 * Transfers API (disbursements to bank accounts and mobile money wallets).
 *
 * Uses OkHttp directly, same as MpesaService/PaypalService — no Flutterwave
 * SDK dependency. See FlutterwaveConfig for the sandbox/live key caveat.
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

    // ─── Standard Checkout (deposits) ────────────────────────────────────────

    public record CheckoutResult(boolean success, String txRef, String checkoutLink, String message) {}

    /**
     * Creates a Flutterwave Standard/Hosted Checkout link. paymentOptions
     * lets the caller restrict which channels are shown on the hosted page
     * (e.g. "card", "banktransfer", "ussd", "mobilemoneyghana", or any
     * comma-separated combination) — pass null to let Flutterwave show
     * every channel enabled on the account.
     *
     * Kenyan mobile money is deliberately NOT routed through here — M-Pesa
     * deposits already go through the direct Daraja STK Push integration
     * (see MpesaService / DepositService.initiateMpesaDeposit). Routing
     * Kenyan mobile money through Flutterwave too would create two
     * competing paths reconciling the same underlying rail.
     */
    public CheckoutResult initiateCheckout(BigDecimal amount, String currency, String txRef,
                                            String customerEmail, String customerName,
                                            String customerPhone, String paymentOptions) {
        Map<String, Object> customer = new HashMap<>();
        customer.put("email", customerEmail);
        if (customerName != null && !customerName.isBlank()) customer.put("name", customerName);
        if (customerPhone != null && !customerPhone.isBlank()) customer.put("phonenumber", customerPhone);

        Map<String, Object> customizations = Map.of(
                "title", "Premisave Wallet Deposit",
                "description", "Fund your Premisave wallet"
        );

        Map<String, Object> body = new HashMap<>();
        body.put("tx_ref", txRef);
        body.put("amount", amount.toPlainString());
        body.put("currency", currency.toUpperCase());
        body.put("redirect_url", config.getRedirectUrl());
        body.put("customer", customer);
        body.put("customizations", customizations);
        if (paymentOptions != null && !paymentOptions.isBlank()) {
            body.put("payment_options", paymentOptions);
        }

        try {
            String responseBody = post("/payments", body);
            log.info("Flutterwave checkout response: {}", responseBody);
            JsonNode node = objectMapper.readTree(responseBody);

            String status = node.path("status").asText("");
            if (!"success".equals(status)) {
                String message = node.path("message").asText("Unknown Flutterwave error");
                log.warn("Flutterwave checkout initiation rejected: txRef={} message={}", txRef, message);
                return new CheckoutResult(false, txRef, null, message);
            }

            String link = node.path("data").path("link").asText(null);
            if (link == null || link.isBlank()) {
                return new CheckoutResult(false, txRef, null,
                        "Flutterwave response missing checkout link: " + responseBody);
            }

            log.info("Flutterwave checkout created: txRef={}", txRef);
            return new CheckoutResult(true, txRef, link, "Checkout link created");
        } catch (Exception e) {
            log.error("Flutterwave checkout initiation failed: txRef={}", txRef, e);
            return new CheckoutResult(false, txRef, null,
                    "Flutterwave checkout initiation failed: " + e.getMessage());
        }
    }

    // ─── Verify transaction (synchronous confirm + webhook double-check) ────

    public record VerifyResult(boolean success, String status, String txRef, String flwRef,
                                BigDecimal amount, String currency, String customerEmail,
                                String paymentType, String message) {}

    /**
     * Verifies a transaction by our own tx_ref — used both by the frontend
     * confirm endpoint (DepositService.confirmFlutterwaveDeposit) after the
     * user returns from the hosted checkout redirect, and by the
     * charge.completed webhook handler as a mandatory double-check before
     * crediting. Flutterwave's own docs warn against trusting a webhook
     * payload's amount/status directly — always re-verify server-side.
     */
    public VerifyResult verifyTransactionByReference(String txRef) {
        try {
            String responseBody = get("/transactions/verify_by_reference?tx_ref=" + txRef);
            log.info("Flutterwave verify-by-reference response: {}", responseBody);
            return parseVerifyResponse(responseBody, txRef);
        } catch (Exception e) {
            log.error("Flutterwave verify-by-reference failed: txRef={}", txRef, e);
            return new VerifyResult(false, null, txRef, null, null, null, null, null,
                    "Flutterwave verification failed: " + e.getMessage());
        }
    }

    /** Same as above, but by Flutterwave's own numeric transaction id (webhook payload's data.id). */
    public VerifyResult verifyTransactionById(String transactionId) {
        try {
            String responseBody = get("/transactions/" + transactionId + "/verify");
            log.info("Flutterwave verify-by-id response: {}", responseBody);
            return parseVerifyResponse(responseBody, null);
        } catch (Exception e) {
            log.error("Flutterwave verify-by-id failed: transactionId={}", transactionId, e);
            return new VerifyResult(false, null, null, null, null, null, null, null,
                    "Flutterwave verification failed: " + e.getMessage());
        }
    }

    private VerifyResult parseVerifyResponse(String responseBody, String fallbackTxRef) throws Exception {
        JsonNode node = objectMapper.readTree(responseBody);

        String status = node.path("status").asText("");
        if (!"success".equals(status)) {
            String message = node.path("message").asText("Unknown Flutterwave error");
            return new VerifyResult(false, null, fallbackTxRef, null, null, null, null, null, message);
        }

        JsonNode data = node.path("data");
        String txStatus = data.path("status").asText(""); // "successful" | "failed" | "pending"
        String txRef = data.path("tx_ref").asText(fallbackTxRef);
        String flwRef = data.path("flw_ref").asText(null);
        BigDecimal amount = data.path("amount").isMissingNode() || data.path("amount").isNull()
                ? null : data.path("amount").decimalValue();
        String currency = data.path("currency").asText(null);
        String customerEmail = data.path("customer").path("email").asText(null);
        String paymentType = data.path("payment_type").asText(null);

        boolean success = "successful".equalsIgnoreCase(txStatus);
        return new VerifyResult(success, txStatus, txRef, flwRef, amount, currency, customerEmail,
                paymentType, "Verification " + (success ? "succeeded" : "returned status=" + txStatus));
    }

    // ─── Transfers (disbursements) ────────────────────────────────────────────

    public record TransferResult(boolean success, String message, String transferId, String reference) {}

    /**
     * Initiates a Flutterwave Transfer — to either a bank account or a
     * mobile money wallet, depending on what accountBank/accountNumber
     * represent:
     *  - Bank: accountBank = the destination bank's Flutterwave bank code
     *    (see GET /banks/{country}), accountNumber = the account number.
     *  - Mobile money: accountBank = the mobile network's Flutterwave code
     *    for that corridor (e.g. "MPS", "MTN", "AIRTEL" — see Flutterwave's
     *    per-country mobile money docs), accountNumber = the phone number.
     * Same underlying endpoint for both — Flutterwave infers the rail from
     * the account_bank code plus currency together.
     *
     * NOTE: transferring in "USD" only works for corridors Flutterwave
     * explicitly supports for your account — most bank/mobile-money payout
     * rails settle in local currency (KES, NGN, GHS, etc.), not USD.
     * Confirm USD payout support for your specific destination corridor
     * with Flutterwave before relying on this in production.
     */
    public TransferResult initiateTransfer(String accountBank, String accountNumber, BigDecimal amount,
                                            String currency, String reference, String narration,
                                            String beneficiaryName) {
        if (amount.compareTo(config.getTransfer().getMinAmount()) < 0
                || amount.compareTo(config.getTransfer().getMaxAmount()) > 0) {
            return new TransferResult(false,
                    "Amount must be between " + config.getTransfer().getMinAmount()
                            + " and " + config.getTransfer().getMaxAmount() + " " + currency,
                    null, reference);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("account_bank", accountBank);
        body.put("account_number", accountNumber);
        body.put("amount", amount.toPlainString());
        body.put("currency", currency.toUpperCase());
        body.put("reference", reference);
        body.put("narration", narration != null ? narration : "Premisave wallet disbursement");
        if (beneficiaryName != null && !beneficiaryName.isBlank()) {
            body.put("beneficiary_name", beneficiaryName);
        }
        if (config.getTransfer().getCallbackUrl() != null && !config.getTransfer().getCallbackUrl().isBlank()) {
            body.put("callback_url", config.getTransfer().getCallbackUrl());
        }

        try {
            String responseBody = post("/transfers", body);
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

    /** Manual reconciliation query — same use case as MpesaService.queryTransactionStatus. */
    public TransferResult getTransferStatus(String transferId) {
        try {
            String responseBody = get("/transfers/" + transferId);
            log.info("Flutterwave transfer status response: {}", responseBody);
            JsonNode node = objectMapper.readTree(responseBody);

            String status = node.path("status").asText("");
            if (!"success".equals(status)) {
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
     * Flutterwave's webhook "signature" is a plain shared-secret string
     * comparison, NOT an HMAC — you set an arbitrary secret hash value in
     * the Dashboard (Settings → Webhooks → Secret Hash), and every webhook
     * request carries it back verbatim in the "verif-hash" header. There is
     * no cryptographic signing of the payload itself, so this is a weaker
     * guarantee than Stripe/PayPal's signature verification — treat this as
     * a shared-secret allowlist check, not tamper-evidence of the body, and
     * always re-verify the actual transaction/transfer status server-side
     * (see verifyTransactionByReference) rather than trusting the webhook's
     * own amount/status fields directly.
     */
    public boolean verifyWebhookSignature(String receivedHash) {
        if (config.getWebhookSecretHash() == null || config.getWebhookSecretHash().isBlank()) {
            log.error("Flutterwave webhook verification skipped — flutterwave.webhook-secret-hash is not " +
                    "configured. Rejecting webhook as unverifiable.");
            return false;
        }
        if (receivedHash == null) {
            log.warn("Flutterwave webhook rejected — missing verif-hash header");
            return false;
        }
        return config.getWebhookSecretHash().equals(receivedHash);
    }

    // ─── HTTP helpers ─────────────────────────────────────────────────────────

    private String post(String path, Map<String, Object> body) throws Exception {
        String json = objectMapper.writeValueAsString(body);
        RequestBody rb = RequestBody.create(json, MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(config.baseUrl() + path)
                .addHeader("Authorization", "Bearer " + config.getSecretKey())
                .post(rb)
                .build();
        try (Response response = http.newCall(request).execute()) {
            return response.body().string();
        }
    }

    private String get(String path) throws Exception {
        Request request = new Request.Builder()
                .url(config.baseUrl() + path)
                .addHeader("Authorization", "Bearer " + config.getSecretKey())
                .get()
                .build();
        try (Response response = http.newCall(request).execute()) {
            return response.body().string();
        }
    }
}