package com.premisave.wallet.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.premisave.wallet.config.MpesaConfig;
import com.premisave.wallet.dto.MpesaB2CResponse;
import com.premisave.wallet.dto.MpesaStkPushRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MpesaService {

    private final MpesaConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient http = new OkHttpClient();

    // ─── OAuth ───────────────────────────────────────────────────────────────

    /**
     * Fetches a short-lived Bearer token from Daraja.
     * In production, cache this token until ~55 s before expiry.
     */
    public String getAccessToken() {
        String credentials = config.getConsumerKey() + ":" + config.getConsumerSecret();
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

        Request request = new Request.Builder()
                .url(config.baseUrl() + "/oauth/v1/generate?grant_type=client_credentials")
                .addHeader("Authorization", "Basic " + encoded)
                .get()
                .build();

        try (Response response = http.newCall(request).execute()) {
            String body = response.body().string();
            JsonNode node = objectMapper.readTree(body);
            String token = node.path("access_token").asText();
            log.debug("M-Pesa OAuth token obtained");
            return token;
        } catch (Exception e) {
            throw new RuntimeException("Failed to obtain M-Pesa access token", e);
        }
    }

    // ─── STK Push (C2B) ──────────────────────────────────────────────────────

    /**
     * Initiates Lipa Na M-Pesa (STK Push) for customer deposits.
     * Returns the CheckoutRequestID for tracking.
     */
    public String initiateStkPush(MpesaStkPushRequest req) {
        String token     = getAccessToken();
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String password  = Base64.getEncoder().encodeToString(
                (config.getShortcode() + config.getPasskey() + timestamp).getBytes(StandardCharsets.UTF_8));

        // Normalize phone: 0712345678 → 254712345678
        String phone = normalizePhone(req.getPhoneNumber());

        // Map.of() is limited to 10 entries — use LinkedHashMap for 11
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
            String json = objectMapper.writeValueAsString(body);
            RequestBody rb = RequestBody.create(json, MediaType.parse("application/json"));

            Request request = new Request.Builder()
                    .url(config.baseUrl() + "/mpesa/stkpush/v1/processrequest")
                    .addHeader("Authorization", "Bearer " + token)
                    .post(rb)
                    .build();

            try (Response response = http.newCall(request).execute()) {
                String respBody = response.body().string();
                log.info("STK Push response: {}", respBody);
                JsonNode node = objectMapper.readTree(respBody);
                String checkoutId = node.path("CheckoutRequestID").asText();
                return checkoutId.isBlank() ? "STK_PUSH_INITIATED" : checkoutId;
            }
        } catch (Exception e) {
            log.error("STK Push failed", e);
            throw new RuntimeException("M-Pesa STK Push failed: " + e.getMessage(), e);
        }
    }

    // ─── B2C (Disbursement) ───────────────────────────────────────────────────

    /**
     * Sends money from business shortcode to a mobile subscriber.
     * Used for disbursements / wallet cash-outs.
     */
    public MpesaB2CResponse sendB2C(String phone, BigDecimal amount) {
        String token = getAccessToken();
        String phone254 = normalizePhone(phone);

        // B2C initiator password is base64(InitiatorPassword) — stored in config or env
        String initiatorPassword = System.getenv().getOrDefault("MPESA_INITIATOR_PASSWORD", "Safaricom999!");
        String securityCredential = Base64.getEncoder()
                .encodeToString(initiatorPassword.getBytes(StandardCharsets.UTF_8));

        Map<String, Object> body = Map.of(
                "InitiatorName", "testapi",
                "SecurityCredential", securityCredential,
                "CommandID", "BusinessPayment",
                "Amount", amount.intValue(),
                "PartyA", config.getShortcode(),
                "PartyB", phone254,
                "Remarks", "Premisave Disbursement",
                "QueueTimeOutURL", config.getCallbackUrl().replace("/callback", "/b2c/timeout"),
                "ResultURL", config.getCallbackUrl().replace("/callback", "/b2c/result"),
                "Occasion", "Wallet Cashout"
        );

        try {
            String json = objectMapper.writeValueAsString(body);
            RequestBody rb = RequestBody.create(json, MediaType.parse("application/json"));

            Request request = new Request.Builder()
                    .url(config.baseUrl() + "/mpesa/b2c/v3/paymentrequest")
                    .addHeader("Authorization", "Bearer " + token)
                    .post(rb)
                    .build();

            try (Response response = http.newCall(request).execute()) {
                String respBody = response.body().string();
                log.info("B2C response: {}", respBody);
                JsonNode node = objectMapper.readTree(respBody);

                String responseCode = node.path("ResponseCode").asText("1");
                boolean success = "0".equals(responseCode);
                String conversationId = node.path("ConversationID").asText("");
                String originatorId   = node.path("OriginatorConversationID").asText("");
                String message        = node.path("ResponseDescription").asText("Unknown");

                return new MpesaB2CResponse(success, message, conversationId, originatorId);
            }
        } catch (Exception e) {
            log.error("B2C failed", e);
            return new MpesaB2CResponse(false, "B2C failed: " + e.getMessage(), null, null);
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private String normalizePhone(String phone) {
        if (phone == null) return "";
        phone = phone.replaceAll("\\s+", "").replaceAll("[^0-9+]", "");
        if (phone.startsWith("+254")) return phone.substring(1);
        if (phone.startsWith("0"))    return "254" + phone.substring(1);
        if (phone.startsWith("7") || phone.startsWith("1")) return "254" + phone;
        return phone;
    }
}