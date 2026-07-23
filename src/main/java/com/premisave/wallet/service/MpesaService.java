package com.premisave.wallet.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.premisave.wallet.config.MpesaConfig;
import com.premisave.wallet.dto.MpesaB2BRequest;
import com.premisave.wallet.dto.MpesaB2BResponse;
import com.premisave.wallet.dto.MpesaB2CResponse;
import com.premisave.wallet.dto.MpesaStkPushRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class MpesaService {

    private final MpesaConfig config;
    private final MpesaSecurityCredentialService securityCredentialService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient http = new OkHttpClient();

    // ─── OAuth (cached) ──────────────────────────────────────────────────────

    private record CachedToken(String token, Instant expiresAt) {}
    private final AtomicReference<CachedToken> tokenCache = new AtomicReference<>();

    /**
     * Daraja tokens are valid ~3600s. We cache and refresh 60s before expiry
     * so concurrent disbursements don't each pay the OAuth round-trip and we
     * don't hammer the consumer key's rate limit under load.
     */
    public synchronized String getAccessToken() {
        CachedToken cached = tokenCache.get();
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
            return cached.token();
        }

        String credentials = config.getConsumerKey() + ":" + config.getConsumerSecret();
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

        Request request = new Request.Builder()
                .url(config.baseUrl() + "/oauth/v1/generate?grant_type=client_credentials")
                .addHeader("Authorization", "Basic " + encoded)
                .get()
                .build();

        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try (Response response = http.newCall(request).execute()) {
                String body = response.body().string();
                JsonNode node = objectMapper.readTree(body);
                String token = node.path("access_token").asText();
                int expiresIn = node.path("expires_in").asInt(3599);

                if (token.isBlank()) {
                    throw new RuntimeException("Empty access_token in OAuth response: " + body);
                }

                CachedToken fresh = new CachedToken(token, Instant.now().plusSeconds(Math.max(60, expiresIn - 60)));
                tokenCache.set(fresh);
                return token;
            } catch (Exception e) {
                lastError = new RuntimeException("Failed to obtain M-Pesa access token (attempt " + attempt + "/3)", e);
                log.warn(lastError.getMessage());
                sleepBackoff(attempt);
            }
        }
        throw lastError;
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(200L * attempt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    // ─── STK Push (C2B — customer-initiated deposit) ────────────────────────

    public String initiateStkPush(MpesaStkPushRequest req) {
        String token     = getAccessToken();
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String password  = Base64.getEncoder().encodeToString(
                (config.getShortcode() + config.getPasskey() + timestamp).getBytes(StandardCharsets.UTF_8));

        String phone = normalizePhone(req.getPhoneNumber());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("BusinessShortCode", config.getShortcode());
        body.put("Password",          password);
        body.put("Timestamp",         timestamp);
        body.put("TransactionType",   "CustomerPayBillOnline");
        body.put("Amount",            req.getAmount().intValue());
        body.put("PartyA",            phone);
        body.put("PartyB",            config.getShortcode());
        body.put("PhoneNumber",       phone);
        body.put("CallBackURL",       config.getCallbackUrl());
        body.put("AccountReference",  req.getAccountReference());
        body.put("TransactionDesc",   "Premisave Wallet Deposit");

        try {
            String respBody = post(config.baseUrl() + "/mpesa/stkpush/v1/processrequest", token, body);
            log.info("STK Push response: {}", respBody);
            JsonNode node = objectMapper.readTree(respBody);
            String checkoutId = node.path("CheckoutRequestID").asText();
            return checkoutId.isBlank() ? "STK_PUSH_INITIATED" : checkoutId;
        } catch (Exception e) {
            log.error("STK Push failed", e);
            throw new RuntimeException("M-Pesa STK Push failed: " + e.getMessage(), e);
        }
    }

    // ─── B2C (Business to Customer — disbursement to a phone number) ────────

    /**
     * Initiates a B2C payment. Returns immediately with the request's
     * ACCEPTANCE status only — Safaricom queues it and reports the real
     * success/failure later via mpesa.daraja.b2c.result-url. Callers must
     * NOT treat isSuccess()==true here as "money delivered" — see
     * DisbursementService.completeMpesaDisbursement() for the actual
     * finalization, which happens off the callback.
     */
    public MpesaB2CResponse sendB2C(String phone, BigDecimal amount) {
        MpesaConfig.B2c b2c = config.getB2c();

        if (amount.compareTo(b2c.getMinAmount()) < 0 || amount.compareTo(b2c.getMaxAmount()) > 0) {
            return new MpesaB2CResponse(false,
                    "Amount must be between " + b2c.getMinAmount() + " and " + b2c.getMaxAmount() + " KES",
                    null, null);
        }

        String token = getAccessToken();
        String phone254 = normalizePhone(phone);
        String securityCredential = securityCredentialService.encrypt(
                b2c.getInitiatorPassword(), config.getCertificatePath());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("InitiatorName",       b2c.getInitiatorName());
        body.put("SecurityCredential",  securityCredential);
        body.put("CommandID",           b2c.getCommandId() != null ? b2c.getCommandId() : "BusinessPayment");
        body.put("Amount",              amount.intValue());
        body.put("PartyA",              b2c.getShortcode() != null ? b2c.getShortcode() : config.getShortcode());
        body.put("PartyB",              phone254);
        body.put("Remarks",             "Premisave Disbursement");
        body.put("QueueTimeOutURL",     b2c.getQueueTimeoutUrl());
        body.put("ResultURL",           b2c.getResultUrl());
        body.put("Occasion",            "Wallet Cashout");

        try {
            String respBody = post(config.baseUrl() + "/mpesa/b2c/v3/paymentrequest", token, body);
            log.info("B2C response: {}", respBody);
            JsonNode node = objectMapper.readTree(respBody);

            boolean accepted = "0".equals(node.path("ResponseCode").asText("1"));
            String conversationId = node.path("ConversationID").asText("");
            String originatorId   = node.path("OriginatorConversationID").asText("");
            String message        = node.path("ResponseDescription").asText("Unknown");

            return new MpesaB2CResponse(accepted, message, conversationId, originatorId);
        } catch (Exception e) {
            log.error("B2C initiation failed", e);
            return new MpesaB2CResponse(false, "B2C initiation failed: " + e.getMessage(), null, null);
        }
    }

    // ─── B2B (Business to Business — payment to another paybill/till) ──────

    /**
     * Initiates a B2B payment. Same async caveat as sendB2C — a "true"
     * result here means Safaricom accepted the request, not that funds
     * have moved. B2B is a permissioned API; confirm it's enabled for your
     * shortcode via your Safaricom account manager / Daraja portal.
     */
    public MpesaB2BResponse sendB2B(MpesaB2BRequest req) {
        MpesaConfig.B2b b2b = config.getB2b();

        if (req.getAmount().compareTo(b2b.getMinAmount()) < 0 || req.getAmount().compareTo(b2b.getMaxAmount()) > 0) {
            return new MpesaB2BResponse(false,
                    "Amount must be between " + b2b.getMinAmount() + " and " + b2b.getMaxAmount() + " KES",
                    null, null);
        }

        String token = getAccessToken();
        String securityCredential = securityCredentialService.encrypt(
                b2b.getInitiatorPassword(), config.getCertificatePath());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("Initiator",              b2b.getInitiatorName());
        body.put("SecurityCredential",     securityCredential);
        body.put("CommandID",              req.getCommandId() != null ? req.getCommandId() : b2b.getCommandId());
        body.put("SenderIdentifierType",   b2b.getSenderIdentifierType());
        // Field name below intentionally matches Safaricom's own (misspelled) API field.
        body.put("RecieverIdentifierType", b2b.getReceiverIdentifierType());
        body.put("Amount",                 req.getAmount().intValue());
        body.put("PartyA",                 b2b.getShortcode());
        body.put("PartyB",                 req.getReceiverShortcode());
        body.put("AccountReference",       req.getAccountReference());
        body.put("Remarks",                req.getRemarks() != null ? req.getRemarks() : "Premisave B2B payment");
        body.put("QueueTimeOutURL",        b2b.getQueueTimeoutUrl());
        body.put("ResultURL",              b2b.getResultUrl());

        try {
            String respBody = post(config.baseUrl() + "/mpesa/b2b/v1/paymentrequest", token, body);
            log.info("B2B response: {}", respBody);
            JsonNode node = objectMapper.readTree(respBody);

            boolean accepted = "0".equals(node.path("ResponseCode").asText("1"));
            String conversationId = node.path("ConversationID").asText("");
            String originatorId   = node.path("OriginatorConversationID").asText("");
            String message        = node.path("ResponseDescription").asText("Unknown");

            return new MpesaB2BResponse(accepted, message, conversationId, originatorId);
        } catch (Exception e) {
            log.error("B2B initiation failed", e);
            return new MpesaB2BResponse(false, "B2B initiation failed: " + e.getMessage(), null, null);
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private String post(String url, String token, Map<String, Object> body) throws Exception {
        String json = objectMapper.writeValueAsString(body);
        RequestBody rb = RequestBody.create(json, MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + token)
                .post(rb)
                .build();
        try (Response response = http.newCall(request).execute()) {
            return response.body().string();
        }
    }

    private String normalizePhone(String phone) {
        if (phone == null) return "";
        phone = phone.replaceAll("\\s+", "").replaceAll("[^0-9+]", "");
        if (phone.startsWith("+254")) return phone.substring(1);
        if (phone.startsWith("0"))    return "254" + phone.substring(1);
        if (phone.startsWith("7") || phone.startsWith("1")) return "254" + phone;
        return phone;
    }
}