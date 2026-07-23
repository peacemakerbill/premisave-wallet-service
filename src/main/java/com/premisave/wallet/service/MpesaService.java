package com.premisave.wallet.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.premisave.wallet.config.MpesaConfig;
import com.premisave.wallet.dto.B2BExpressCheckoutResponse;
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

    // ─── B2B (Business to Business — BusinessPayBill / BusinessBuyGoods / etc.) ──

    /**
     * Initiates a generic B2B payment via /mpesa/b2b/v1/paymentrequest.
     * Covers BusinessPayBill (pay another paybill) and BusinessBuyGoods (pay
     * a till/store number) — the request shape is identical for both per
     * Safaricom's spec; only CommandID (and typically the identifier types)
     * differ. Same async caveat as sendB2C — a "true" result here means
     * Safaricom accepted the request, not that funds have moved.
     * B2B is a permissioned API; confirm it's enabled for your shortcode.
     *
     * See https://developer.safaricom.co.ke/apis/BusinessBuyGoods for the
     * BusinessBuyGoods variant specifically.
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

    // ─── B2B Express Checkout (USSD Push to Till) ───────────────────────────

    /**
     * Triggers a USSD Push to a merchant's till, prompting them to pay one
     * of our shortcodes (mpesa.daraja.express-checkout.receiver-shortcode)
     * directly from their till. See
     * https://developer.safaricom.co.ke/apis/B2BExpressCheckout
     *
     * Unlike the other B2B/B2C calls, Safaricom's acknowledgement here is
     * just {"code":"0","status":"..."} — no ConversationID. The real outcome
     * (success/cancelled/failed) arrives later via the express-checkout
     * callback URL, keyed by the RequestRefID we generate and pass in here,
     * since the callback body carries no account/email either
     * (see DepositService.creditWalletFromExpressCheckout).
     */
    public B2BExpressCheckoutResponse initiateExpressCheckout(String payerTillNumber, BigDecimal amount,
                                                                String paymentRef, String requestRefId) {
        String token = getAccessToken();
        MpesaConfig.ExpressCheckout ec = config.getExpressCheckout();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("primaryShortCode", payerTillNumber);
        body.put("receiverShortCode", ec.getReceiverShortCode());
        body.put("amount", amount.toPlainString());
        body.put("paymentRef", paymentRef);
        body.put("callbackUrl", ec.getCallbackUrl());
        body.put("partnerName", ec.getPartnerName());
        body.put("RequestRefID", requestRefId);

        try {
            String respBody = post(config.baseUrl() + "/v1/ussdpush/get-msisdn", token, body);
            log.info("B2B Express Checkout response: {}", respBody);
            JsonNode node = objectMapper.readTree(respBody);

            String code = node.path("code").asText("");
            String status = node.path("status").asText("Unknown");
            boolean accepted = "0".equals(code);

            return new B2BExpressCheckoutResponse(accepted, requestRefId, status);
        } catch (Exception e) {
            log.error("B2B Express Checkout initiation failed", e);
            return new B2BExpressCheckoutResponse(false, requestRefId,
                    "Express Checkout initiation failed: " + e.getMessage());
        }
    }

    // ─── B2C Account Top Up ──────────────────────────────────────────────────

    /**
     * Loads funds from Premisave's working/MMF account into a B2C
     * shortcode's utility account via CommandID "BusinessPayToBulk", so
     * disbursements don't run dry. See
     * https://developer.safaricom.co.ke/apis/B2CAccountTopUp
     *
     * Reuses the /mpesa/b2b/v1/paymentrequest endpoint and the existing B2B
     * result/timeout callbacks for reconciliation, keyed by ConversationID
     * like every other B2B call — see DisbursementService.processB2CTopUp
     * and completeMpesaDisbursement.
     */
    public MpesaB2BResponse topUpB2CAccount(BigDecimal amount, String receivingShortcode,
                                              String requester, String accountReference, String remarks) {
        MpesaConfig.AccountTopUp topUp = config.getAccountTopUp();
        String token = getAccessToken();
        String securityCredential = securityCredentialService.encrypt(
                topUp.getInitiatorPassword(), config.getCertificatePath());

        String targetShortcode = receivingShortcode != null ? receivingShortcode : config.getB2c().getShortcode();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("Initiator", topUp.getInitiatorName());
        body.put("SecurityCredential", securityCredential);
        body.put("CommandID", "BusinessPayToBulk");
        body.put("SenderIdentifierType", "4");
        // Field name below intentionally matches Safaricom's own (misspelled) API field.
        body.put("RecieverIdentifierType", "4");
        body.put("Amount", amount.intValue());
        body.put("PartyA", topUp.getPartyA());
        body.put("PartyB", targetShortcode);
        body.put("AccountReference", accountReference != null ? accountReference : "B2C-TOPUP");
        if (requester != null && !requester.isBlank()) {
            body.put("Requester", requester);
        }
        body.put("Remarks", remarks != null ? remarks : "B2C account top-up");
        body.put("QueueTimeOutURL", topUp.getQueueTimeoutUrl());
        body.put("ResultURL", topUp.getResultUrl());

        try {
            String respBody = post(config.baseUrl() + "/mpesa/b2b/v1/paymentrequest", token, body);
            log.info("B2C Account Top Up response: {}", respBody);
            JsonNode node = objectMapper.readTree(respBody);

            boolean accepted = "0".equals(node.path("ResponseCode").asText("1"));
            String conversationId = node.path("ConversationID").asText("");
            String originatorId   = node.path("OriginatorConversationID").asText("");
            String message        = node.path("ResponseDescription").asText("Unknown");

            return new MpesaB2BResponse(accepted, message, conversationId, originatorId);
        } catch (Exception e) {
            log.error("B2C Account Top Up failed", e);
            return new MpesaB2BResponse(false, "B2C Account Top Up failed: " + e.getMessage(), null, null);
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