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
     * ResponseType options: "Completed" (skip validation, confirm directly)
     *                       "Cancelled" (reject if validation URL is down)
     * Use "Completed" in production unless you have strict validation needs.
     */
    public Map<String, Object> registerUrls() {
        String token = mpesaService.getAccessToken();

        // Derive C2B URLs from the base callback URL in config
        String base = config.getCallbackUrl()
                .replace("/payments/mpesa/callback", ""); // strip STK suffix

        Map<String, Object> body = Map.of(
                "ShortCode",        config.getShortcode(),
                "ResponseType",     "Completed",   // or "Cancelled"
                "ConfirmationURL",  base + "/payments/mpesa/c2b/confirmation",
                "ValidationURL",    base + "/payments/mpesa/c2b/validation"
        );

        try {
            String json = objectMapper.writeValueAsString(body);
            RequestBody rb = RequestBody.create(json, MediaType.parse("application/json"));

            Request request = new Request.Builder()
                    .url(config.baseUrl() + "/mpesa/c2b/v1/registerurl")
                    .addHeader("Authorization", "Bearer " + token)
                    .post(rb)
                    .build();

            try (Response response = http.newCall(request).execute()) {
                String respBody = response.body().string();
                log.info("C2B URL registration response: {}", respBody);
                JsonNode node = objectMapper.readTree(respBody);
                return Map.of(
                        "ResponseCode",        node.path("ResponseCode").asText(),
                        "ResponseDescription", node.path("ResponseDescription").asText(),
                        "CustomerMessage",     node.path("CustomerMessage").asText()
                );
            }
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