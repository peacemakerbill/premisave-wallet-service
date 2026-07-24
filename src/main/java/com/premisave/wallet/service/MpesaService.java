package com.premisave.wallet.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.premisave.wallet.config.MpesaConfig;
import com.premisave.wallet.dto.B2BExpressCheckoutResponse;
import com.premisave.wallet.dto.B2PochiRequest;
import com.premisave.wallet.dto.MpesaAsyncResponse;
import com.premisave.wallet.dto.MpesaB2BRequest;
import com.premisave.wallet.dto.MpesaB2BResponse;
import com.premisave.wallet.dto.MpesaB2CResponse;
import com.premisave.wallet.dto.MpesaReversalRequest;
import com.premisave.wallet.dto.MpesaStkPushRequest;
import com.premisave.wallet.dto.PullTransactionRecord;
import com.premisave.wallet.dto.PullTransactionResponse;
import com.premisave.wallet.dto.QueryOrgInfoRequest;
import com.premisave.wallet.dto.QueryOrgInfoResponse;
import com.premisave.wallet.dto.TransactionStatusRequest;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    // ─── Account Balance ─────────────────────────────────────────────────────

    /**
     * Queries the real-time balance (Working/MMF, Utility, Charges Paid,
     * Organization Settlement accounts) for our own shortcode. Result
     * arrives later via ResultURL as a pipe-delimited string per account —
     * see MpesaOperationsService for parsing.
     * See https://developer.safaricom.co.ke/apis/AccountBalance
     */
    public MpesaAsyncResponse queryAccountBalance() {
        MpesaConfig.AccountBalance cfg = config.getAccountBalance();
        String token = getAccessToken();
        String securityCredential = securityCredentialService.encrypt(
                cfg.getInitiatorPassword(), config.getCertificatePath());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("Initiator", cfg.getInitiatorName());
        body.put("SecurityCredential", securityCredential);
        body.put("CommandID", "AccountBalance");
        body.put("PartyA", cfg.getPartyA() != null ? cfg.getPartyA() : config.getShortcode());
        body.put("IdentifierType", cfg.getIdentifierType());
        body.put("Remarks", "Premisave balance inquiry");
        body.put("QueueTimeOutURL", cfg.getQueueTimeoutUrl());
        body.put("ResultURL", cfg.getResultUrl());

        try {
            String respBody = post(config.baseUrl() + "/mpesa/accountbalance/v1/query", token, body);
            log.info("Account Balance response: {}", respBody);
            return parseAsyncAck(respBody, "Account Balance");
        } catch (Exception e) {
            log.error("Account Balance query failed", e);
            return new MpesaAsyncResponse(false, "Account Balance query failed: " + e.getMessage(), null, null);
        }
    }

    // ─── Transaction Status ──────────────────────────────────────────────────

    /**
     * Secondary reconciliation mechanism for C2B/B2B/B2C/Reversal
     * transactions when the original ResultURL callback never arrived.
     * Requires either transactionId (M-Pesa receipt) or originatorConversationId.
     * See https://developer.safaricom.co.ke/apis/TransactionStatus
     */
    public MpesaAsyncResponse queryTransactionStatus(TransactionStatusRequest req) {
        boolean hasTxId = req.getTransactionId() != null && !req.getTransactionId().isBlank();
        boolean hasOcid = req.getOriginatorConversationId() != null && !req.getOriginatorConversationId().isBlank();
        if (!hasTxId && !hasOcid) {
            return new MpesaAsyncResponse(false,
                    "Either transactionId or originatorConversationId is required", null, null);
        }

        MpesaConfig.TransactionStatus cfg = config.getTransactionStatus();
        String token = getAccessToken();
        String securityCredential = securityCredentialService.encrypt(
                cfg.getInitiatorPassword(), config.getCertificatePath());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("Initiator", cfg.getInitiatorName());
        body.put("SecurityCredential", securityCredential);
        body.put("CommandID", "TransactionStatusQuery");
        if (hasTxId) {
            body.put("TransactionID", req.getTransactionId());
        }
        if (hasOcid) {
            body.put("OriginalConversationID", req.getOriginatorConversationId());
        }
        body.put("PartyA", cfg.getPartyA() != null ? cfg.getPartyA() : config.getShortcode());
        body.put("IdentifierType", cfg.getIdentifierType());
        body.put("ResultURL", cfg.getResultUrl());
        body.put("QueueTimeOutURL", cfg.getQueueTimeoutUrl());
        body.put("Remarks", req.getRemarks() != null ? req.getRemarks() : "Status check");
        if (req.getOccasion() != null && !req.getOccasion().isBlank()) {
            body.put("Occasion", req.getOccasion());
        }

        try {
            String respBody = post(config.baseUrl() + "/mpesa/transactionstatus/v1/query", token, body);
            log.info("Transaction Status response: {}", respBody);
            return parseAsyncAck(respBody, "Transaction Status");
        } catch (Exception e) {
            log.error("Transaction Status query failed", e);
            return new MpesaAsyncResponse(false, "Transaction Status query failed: " + e.getMessage(), null, null);
        }
    }

    // ─── Reversal ─────────────────────────────────────────────────────────────

    /**
     * Reverses a completed C2B transaction — refunds the customer and debits
     * our shortcode. Per Safaricom's spec, B2C payouts cannot be reversed via
     * this API (portal only), so this should only ever be called against a
     * C2B/STK deposit's M-Pesa receipt number.
     * See https://developer.safaricom.co.ke/apis/Reversal
     */
    public MpesaAsyncResponse initiateReversal(MpesaReversalRequest req) {
        MpesaConfig.Reversal cfg = config.getReversal();
        String token = getAccessToken();
        String securityCredential = securityCredentialService.encrypt(
                cfg.getInitiatorPassword(), config.getCertificatePath());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("Initiator", cfg.getInitiatorName());
        body.put("SecurityCredential", securityCredential);
        body.put("CommandID", "TransactionReversal");
        body.put("TransactionID", req.getTransactionId());
        body.put("Amount", req.getAmount().intValue());
        body.put("ReceiverParty", cfg.getReceiverParty() != null ? cfg.getReceiverParty() : config.getShortcode());
        // Field name below intentionally matches Safaricom's own (misspelled) API field.
        body.put("RecieverIdentifierType", cfg.getReceiverIdentifierType());
        body.put("ResultURL", cfg.getResultUrl());
        body.put("QueueTimeOutURL", cfg.getQueueTimeoutUrl());
        body.put("Remarks", req.getRemarks());

        try {
            String respBody = post(config.baseUrl() + "/mpesa/reversal/v1/request", token, body);
            log.info("Reversal response: {}", respBody);
            return parseAsyncAck(respBody, "Reversal");
        } catch (Exception e) {
            log.error("Reversal initiation failed", e);
            return new MpesaAsyncResponse(false, "Reversal initiation failed: " + e.getMessage(), null, null);
        }
    }

    // ─── B2Pochi (Business to Pochi la Biashara) ────────────────────────────

    /**
     * Pays directly into a customer's Pochi la Biashara business wallet
     * (CommandID BusinessPayToPochi) rather than their main M-Pesa balance.
     * Requires our own OriginatorConversationID up front (Safaricom uses it
     * to prevent double-disbursement) — we generate one and store it
     * alongside the returned ConversationID for reconciliation.
     * See https://developer.safaricom.co.ke/apis/BusinessToPochi
     */
    public MpesaAsyncResponse sendToPochi(B2PochiRequest req, String originatorConversationId) {
        MpesaConfig.B2Pochi cfg = config.getB2Pochi();

        if (req.getAmount().compareTo(cfg.getMinAmount()) < 0 || req.getAmount().compareTo(cfg.getMaxAmount()) > 0) {
            return new MpesaAsyncResponse(false,
                    "Amount must be between " + cfg.getMinAmount() + " and " + cfg.getMaxAmount() + " KES",
                    null, null);
        }

        String token = getAccessToken();
        String phone254 = normalizePhone(req.getPhoneNumber());
        String securityCredential = securityCredentialService.encrypt(
                cfg.getInitiatorPassword(), config.getCertificatePath());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("OriginatorConversationID", originatorConversationId);
        body.put("InitiatorName", cfg.getInitiatorName());
        body.put("SecurityCredential", securityCredential);
        body.put("CommandID", "BusinessPayToPochi");
        body.put("Amount", req.getAmount().intValue());
        body.put("PartyA", cfg.getPartyA() != null ? cfg.getPartyA() : config.getShortcode());
        body.put("PartyB", phone254);
        body.put("Remarks", req.getRemarks() != null ? req.getRemarks() : "Premisave Pochi disbursement");
        body.put("QueueTimeOutURL", cfg.getQueueTimeoutUrl());
        body.put("ResultURL", cfg.getResultUrl());
        // Safaricom's own field is spelled "Occassion" (double-s) for B2Pochi
        // specifically — differs from every other M-Pesa API. Match exactly.
        body.put("Occassion", req.getOccasion() != null ? req.getOccasion() : "");

        try {
            String respBody = post(config.baseUrl() + "/mpesa/b2pochi/v1/paymentrequest", token, body);
            log.info("B2Pochi response: {}", respBody);
            return parseAsyncAck(respBody, "B2Pochi");
        } catch (Exception e) {
            log.error("B2Pochi initiation failed", e);
            return new MpesaAsyncResponse(false, "B2Pochi initiation failed: " + e.getMessage(), null, null);
        }
    }

    // ─── Pull Transactions (C2B reconciliation) ─────────────────────────────

    /**
     * One-time registration of our shortcode for the Pull Transactions API.
     * Unlike B2C/B2B/Reversal/Balance/TransactionStatus, this needs no
     * initiator/SecurityCredential — just the OAuth bearer token, same as
     * STK Push and C2B Register URL.
     * See https://developer.safaricom.co.ke/apis/PullTransaction
     */
    public PullTransactionResponse registerPullTransactions() {
        MpesaConfig.PullTransactions cfg = config.getPullTransactions();
        String token = getAccessToken();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ShortCode", cfg.getShortcode() != null ? cfg.getShortcode() : config.getShortcode());
        body.put("RequestType", "Pull");
        body.put("NominatedNumber", cfg.getNominatedNumber());
        body.put("CallBackURL", cfg.getCallbackUrl());

        try {
            String respBody = post(config.baseUrl() + "/pulltransactions/v1/register", token, body);
            log.info("Pull Transactions registration response: {}", respBody);
            JsonNode node = objectMapper.readTree(respBody);

            String status = node.path("ResponseStatus").asText("");
            // 1000 = registered now, 1001 = already registered — both are fine to treat as success.
            boolean success = "1000".equals(status) || "1001".equals(status);
            String message = node.path("ResponseDescription").asText("Unknown");
            String refId = node.path("ResponseRefID").asText("");
            String shortCode = node.path("ShortCode").asText("");

            return new PullTransactionResponse(success, message, refId, shortCode, null, null, null, null);
        } catch (Exception e) {
            log.error("Pull Transactions registration failed", e);
            return new PullTransactionResponse(false, "Pull Transactions registration failed: " + e.getMessage(),
                    null, null, null, null, null, null);
        }
    }

    /**
     * Queries all C2B transactions on our shortcode within the given window
     * (Safaricom retains up to 48 hours). Returns the raw list of records —
     * reconciliation against our own Transaction records happens in
     * PullTransactionService, not here.
     *
     * NOTE on HTTP method: Safaricom's docs state "Method is ... GET for
     * Pull transaction" but the documented request is a JSON body, which
     * standard HTTP GET can't carry (OkHttp explicitly rejects a body on
     * GET). Every working implementation of this endpoint we could verify
     * sends it as POST, same as Register Pull, so that's what's implemented
     * here. If your sandbox testing shows Safaricom rejects POST for this
     * specific endpoint, switch the method below — the request/response
     * shape stays the same either way.
     */
    public PullTransactionResponse queryPullTransactions(LocalDateTime startDate, LocalDateTime endDate, int offsetValue) {
        MpesaConfig.PullTransactions cfg = config.getPullTransactions();
        String token = getAccessToken();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ShortCode", cfg.getShortcode() != null ? cfg.getShortcode() : config.getShortcode());
        body.put("StartDate", startDate.format(fmt));
        body.put("EndDate", endDate.format(fmt));
        body.put("OffSetValue", String.valueOf(offsetValue));

        try {
            String respBody = post(config.baseUrl() + "/pulltransactions/v1/query", token, body);
            log.info("Pull Transactions query response: {}", respBody);
            return parsePullTransactionsResponse(respBody);
        } catch (Exception e) {
            log.error("Pull Transactions query failed", e);
            return new PullTransactionResponse(false, "Pull Transactions query failed: " + e.getMessage(),
                    null, null, List.of(), null, null, null);
        }
    }

    /**
     * Shared parser for the Query Pull Transaction response — also reusable
     * for whatever Safaricom posts to our CallBackURL, since the per-record
     * field shape should be the same (see PullTransactionService).
     */
    public PullTransactionResponse parsePullTransactionsResponse(String respBody) throws Exception {
        JsonNode node = objectMapper.readTree(respBody);

        String responseCode = node.path("ResponseCode").asText(node.path("ResponseStatus").asText(""));
        boolean success = "0".equals(responseCode) || "1000".equals(responseCode);
        String message = node.path("ResponseMessage").asText(node.path("ResponseDescription").asText("Unknown"));
        String refId = node.path("ResponseRefID").asText("");

        List<PullTransactionRecord> records = new ArrayList<>();
        JsonNode txArray = node.has("Transaction") ? node.path("Transaction") : node.path("transactions");
        if (txArray.isArray()) {
            for (JsonNode txNode : txArray) {
                // Safaricom's sample shows a possible extra nesting level for
                // empty results ("Transaction": "[[]]") — skip anything that
                // isn't itself an object.
                if (txNode.isObject()) {
                    records.add(objectMapper.treeToValue(txNode, PullTransactionRecord.class));
                }
            }
        }

        return new PullTransactionResponse(success, message, refId, null, records, null, null, null);
    }

    // ─── B2B Hakikisha (Query Org Info) ─────────────────────────────────────

    /**
     * Looks up the registered name and charge/tariff profile for a given
     * shortcode/till — meant to be called before a B2B payment so the
     * recipient can be confirmed and fees estimated up front. Synchronous:
     * unlike every other operational API here, the answer comes back in the
     * HTTP response itself, no ResultURL involved.
     * See https://developer.safaricom.co.ke/apis/QueryOrgInfo
     */
    public QueryOrgInfoResponse queryOrgInfo(QueryOrgInfoRequest req) {
        String queryUrl = config.queryOrgInfoUrl();
        String token = getAccessToken();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("IdentifierType", req.getIdentifierType());
        body.put("Identifier", req.getIdentifier());

        try {
            String respBody = post(queryUrl, token, body);
            log.info("Query Org Info response: {}", respBody);
            JsonNode node = objectMapper.readTree(respBody);

            String organizationName = node.path("OrganizationName").asText("");
            // Safaricom's own docs are inconsistent about what ResponseCode means for
            // this API (sample success shows "4000"; the Error Codes table says "0" is
            // success). Deriving success from OrganizationName being present sidesteps
            // that ambiguity rather than trusting either convention blindly.
            boolean success = !organizationName.isBlank();

            return new QueryOrgInfoResponse(
                    success,
                    node.path("ConversationID").asText(""),
                    node.path("ResponseCode").asText(""),
                    node.path("ResponseMessage").asText("Unknown"),
                    node.path("DetailedMessage").asText(""),
                    node.path("OrganizationShortCode").asText(""),
                    organizationName,
                    node.path("ChargeProfileID").asText("")
            );
        } catch (Exception e) {
            log.error("Query Org Info failed", e);
            return new QueryOrgInfoResponse(false, null, null,
                    "Query Org Info failed: " + e.getMessage(), null, null, null, null);
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Shared parser for the four "operational" APIs above — all return the
     * same {ResponseCode, ResponseDescription, ConversationID,
     * OriginatorConversationID} acknowledgement shape.
     */
    private MpesaAsyncResponse parseAsyncAck(String respBody, String apiName) throws Exception {
        JsonNode node = objectMapper.readTree(respBody);
        boolean accepted = "0".equals(node.path("ResponseCode").asText("1"));
        String conversationId = node.path("ConversationID").asText("");
        String originatorId   = node.path("OriginatorConversationID").asText("");
        String message        = node.path("ResponseDescription").asText(apiName + " request submitted");
        return new MpesaAsyncResponse(accepted, message, conversationId, originatorId);
    }

    /** Generates a Safaricom-safe OriginatorConversationID for APIs (like B2Pochi) that require one up front. */
    public String generateOriginatorConversationId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

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