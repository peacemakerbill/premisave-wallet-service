package com.premisave.wallet.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.premisave.wallet.client.AuthServiceClient;
import com.premisave.wallet.config.MpesaConfig;
import com.premisave.wallet.dto.MpesaC2BCallbackRequest;
import com.premisave.wallet.entity.Transaction;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.TransactionStatus;
import com.premisave.wallet.enums.TransactionType;
import com.premisave.wallet.exception.WalletNotFoundException;
import com.premisave.wallet.repository.TransactionRepository;
import com.premisave.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MpesaC2BService {

    private final MpesaConfig config;
    private final MpesaService mpesaService;   // reuse getAccessToken()
    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final AuthServiceClient authServiceClient;

    private final OkHttpClient http = new OkHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ─── URL Registration ─────────────────────────────────────────────────────

    /**
     * Registers validation + confirmation URLs with Safaricom.
     * Call this ONCE after deployment (or on every startup — Safaricom is idempotent).
     *
     * Uses mpesa.daraja.c2b.* directly (shortcode, response-type, validation-url,
     * confirmation-url) instead of deriving paths from the STK callback URL —
     * the C2B test shortcode is different from the STK Push test shortcode
     * in Daraja sandbox, so they must be configured independently.
     *
     * Safaricom uses TWO DIFFERENT response shapes here, and both are valid
     * JSON, so parsing alone doesn't tell you which one you got:
     *   - Rejection:  {"requestId": "...", "errorCode": "...", "errorMessage": "..."}
     *   - Acceptance: {"ResponseCode": "0", "ResponseDescription": "success", ...}
     * Blindly reading ResponseCode/ResponseDescription/CustomerMessage off an
     * error payload just returns empty strings for all three — which used to
     * get wrapped in ApiResponse.success(...) by the controller, masking a
     * real failure as a fake success. This now throws on either shape of
     * rejection so the failure surfaces honestly instead.
     *
     * ResponseCode success value is NOT consistently "0" for this specific
     * endpoint — sandbox has been observed returning "00000000" (all zeros)
     * instead of a bare "0" (unlike STK Push/B2B/B2C, which do use a bare
     * "0"). An exact-match check against "0" therefore misclassifies a real
     * Safaricom success as a failure. success is now determined by BOTH:
     *   1. ResponseCode being present and consisting entirely of '0' chars
     *      (covers "0", "00000000", or any other zero-padding Safaricom
     *      might use), OR
     *   2. ResponseDescription itself reading "success" (case-insensitive),
     *      as a fallback in case ResponseCode is ever blank/malformed but
     *      the description clearly indicates acceptance.
     */
    public Map<String, Object> registerUrls() {
        String token = mpesaService.getAccessToken();

        Map<String, Object> body = Map.of(
                "ShortCode",        config.getC2b().getShortcode(),
                "ResponseType",     config.getC2b().getResponseType(),
                "ConfirmationURL",  config.getC2b().getConfirmationUrl(),
                "ValidationURL",    config.getC2b().getValidationUrl()
        );

        try {
            String json = objectMapper.writeValueAsString(body);
            RequestBody rb = RequestBody.create(json, MediaType.parse("application/json"));

            Request request = new Request.Builder()
                    .url(config.baseUrl() + "/mpesa/c2b/v2/registerurl")
                    .addHeader("Authorization", "Bearer " + token)
                    .post(rb)
                    .build();

            try (Response response = http.newCall(request).execute()) {
                String respBody = response.body().string();
                log.info("C2B URL registration response: {}", respBody);
                JsonNode node = objectMapper.readTree(respBody);

                // ── Rejection shape: {requestId, errorCode, errorMessage} ──────
                String errorCode = node.path("errorCode").asText(null);
                if (errorCode != null && !errorCode.isBlank()) {
                    String errorMessage = node.path("errorMessage").asText("Unknown C2B registration error");
                    log.warn("C2B URL registration rejected by Safaricom: errorCode={} errorMessage={}",
                            errorCode, errorMessage);
                    throw new RuntimeException(
                            "C2B URL registration failed (" + errorCode + "): " + errorMessage);
                }

                // ── Acceptance shape ─────────────────────────────────────────
                String responseCode = node.path("ResponseCode").asText("");
                String description  = node.path("ResponseDescription").asText("");

                boolean codeIndicatesSuccess = !responseCode.isBlank()
                        && responseCode.chars().allMatch(c -> c == '0');
                boolean descriptionIndicatesSuccess = "success".equalsIgnoreCase(description.trim());

                boolean success = codeIndicatesSuccess || descriptionIndicatesSuccess;

                if (!success) {
                    String desc = !description.isBlank() ? description : "Unknown failure";
                    log.warn("C2B URL registration not accepted: responseCode={} description={} raw={}",
                            responseCode, desc, respBody);
                    throw new RuntimeException(
                            "C2B URL registration was not accepted (ResponseCode=" + responseCode + "): " + desc);
                }

                log.info("C2B URL registration accepted: responseCode={} description={}", responseCode, description);

                return Map.of(
                        "success",             true,
                        "ResponseCode",        responseCode,
                        "ResponseDescription", description,
                        "CustomerMessage",     node.path("CustomerMessage").asText()
                );
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("C2B URL registration failed: " + e.getMessage(), e);
        }
    }

    // ─── Validation ───────────────────────────────────────────────────────────

    /**
     * Dual-layer account validation for M-Pesa C2B (external validation enabled).
     *
     * Layer 1 — Auth Service: confirms the email belongs to a real, active,
     *            verified, non-archived user. Calls the internal endpoint via
     *            Feign with X-API-Key. Fail-safe: if auth service is unreachable,
     *            we REJECT (fail closed) — better to decline than accept an unknown account.
     *
     * Layer 2 — Wallet Service: confirms a wallet actually exists locally for
     *            that email, so we have somewhere to credit the funds.
     *
     * Safaricom must receive a response within 8 seconds — both checks are
     * fast indexed lookups designed to stay well within that window.
     */
    public boolean validateAccount(String email) {
        if (email == null || email.isBlank()) {
            log.warn("C2B validation: empty account number received");
            return false;
        }

        String normalizedEmail = email.trim().toLowerCase();

        // ── Layer 1: Verify user exists and is active in auth service ──────
        try {
            Map<String, Object> result = authServiceClient.validateEmail(normalizedEmail);
            boolean valid = Boolean.TRUE.equals(result.get("valid"));

            if (!valid) {
                String reason = (String) result.getOrDefault("reason", "UNKNOWN");
                log.warn("C2B validation: auth service rejected email={} reason={}", normalizedEmail, reason);
                return false;
            }

            log.debug("C2B validation: auth service confirmed email={}", normalizedEmail);
        } catch (Exception e) {
            // Auth service is down — FAIL CLOSED (reject payment)
            log.error("C2B validation: auth service unreachable for email={} — rejecting (fail-safe). Error: {}",
                    normalizedEmail, e.getMessage());
            return false;
        }

        // ── Layer 2: Verify a wallet exists locally to receive funds ───────
        boolean walletExists = walletRepository.findByAccountNumber(normalizedEmail).isPresent();
        if (!walletExists) {
            log.warn("C2B validation: auth account valid but no wallet found for email={}", normalizedEmail);
        }
        return walletExists;
    }

    // ─── Confirmation ─────────────────────────────────────────────────────────

    /**
     * Called after Safaricom confirms the payment on their side.
     * Idempotent — skips duplicate TransIDs already in the DB.
     */
    @Transactional
    public void processConfirmation(MpesaC2BCallbackRequest req) {
        String email     = req.getBillRefNumber().trim().toLowerCase();
        String transId   = req.getTransID();
        BigDecimal amount = new BigDecimal(req.getTransAmount());

        // Idempotency — skip if we've already processed this M-Pesa transaction
        if (transactionRepository.existsByProviderReference(transId)) {
            log.warn("C2B duplicate ignored: transId={}", transId);
            return;
        }

        Wallet wallet = walletRepository.findByAccountNumber(email)
                .orElseThrow(() -> new WalletNotFoundException(
                        "C2B confirmation: no wallet for account=" + email));

        // Credit the wallet
        wallet.setBalance(wallet.getBalance().add(amount));
        walletRepository.save(wallet);

        // Record the transaction
        String senderName = buildSenderName(req);
        String description = String.format("M-Pesa C2B deposit from %s (%s)", senderName, req.getMSISDN());

        Transaction tx = new Transaction();
        tx.setUserId(wallet.getUserId());
        tx.setWalletId(wallet.getId());
        tx.setType(TransactionType.DEPOSIT);
        tx.setStatus(TransactionStatus.COMPLETED);
        tx.setAmount(amount);
        tx.setCurrency(Currency.KES);
        tx.setDescription(description);
        tx.setProviderReference(transId);     // M-Pesa TransID — also our idempotency key
        tx.setReference(transId);
        transactionRepository.save(tx);

        log.info("C2B deposit processed: email={} amount={} transId={} sender={}",
                email, amount, transId, senderName);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private String buildSenderName(MpesaC2BCallbackRequest req) {
        StringBuilder name = new StringBuilder();
        if (req.getFirstName()  != null) name.append(req.getFirstName()).append(" ");
        if (req.getMiddleName() != null && !req.getMiddleName().isBlank())
            name.append(req.getMiddleName()).append(" ");
        if (req.getLastName()   != null) name.append(req.getLastName());
        String result = name.toString().trim();
        return result.isBlank() ? "Unknown" : result;
    }
}