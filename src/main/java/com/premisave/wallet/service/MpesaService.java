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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MpesaService {

    private final MpesaConfig config;
    private final MpesaSecurityCredentialService securityCredentialService;
    private final MpesaTokenService mpesaTokenService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient http = new OkHttpClient();

    // ─── OAuth ────────────────────────────────────────────────────────────
    //
    // Token generation/caching/proactive background refresh now lives in
    // MpesaTokenService (warmed up at application startup, refreshed on a
    // schedule ahead of expiry, all logged there). This method is kept as a
    // thin delegate so every existing call site below (and any external
    // caller) doesn't need to change.

    public String getAccessToken() {
        return mpesaTokenService.getAccessToken();
    }

    // ─── STK Push (C2B — customer-initiated deposit) ────────────────────────

    /**
     * Result of an STK Push initiation attempt.
     *
     * Safaricom uses TWO DIFFERENT response shapes here, and both are valid
     * JSON, so parsing alone doesn't tell you which one you got:
     *   - Rejection:  {"requestId": "...", "errorCode": "400.002.02", "errorMessage": "..."}
     *   - Acceptance: {"MerchantRequestID": "...", "CheckoutRequestID": "...",
     *                  "ResponseCode": "0", "ResponseDescription": "...",
     *                  "CustomerMessage": "..."}
     * success=false on either an HTTP/network failure OR a well-formed
     * Safaricom rejection — callers must check this before treating the
     * push as sent, rather than assuming a well-formed reply means success.
     */
    public record StkPushResult(
            boolean success,
            String checkoutRequestId,
            String merchantRequestId,
            String responseDescription,
            String customerMessage,
            String errorMessage) {}

    public StkPushResult initiateStkPush(MpesaStkPushRequest req) {
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

            // ── Rejection shape: {requestId, errorCode, errorMessage} ──────
            String errorCode = node.path("errorCode").asText(null);
            if (errorCode != null && !errorCode.isBlank()) {
                String errorMessage = node.path("errorMessage").asText("Unknown STK Push error");
                log.warn("STK Push rejected by Safaricom: errorCode={} errorMessage={}", errorCode, errorMessage);
                return new StkPushResult(false, null, null, null, null,
                        errorCode + ": " + errorMessage);
            }

            // ── Acceptance shape ─────────────────────────────────────────
            String checkoutId        = node.path("CheckoutRequestID").asText();
            String merchantRequestId = node.path("MerchantRequestID").asText();
            String responseCode      = node.path("ResponseCode").asText();
            String responseDesc      = node.path("ResponseDescription").asText();
            String customerMessage   = node.path("CustomerMessage").asText();

            boolean accepted = "0".equals(responseCode) && !checkoutId.isBlank();
            if (!accepted) {
                log.warn("STK Push not accepted: responseCode={} responseDesc={} raw={}",
                        responseCode, responseDesc, respBody);
                return new StkPushResult(false, null, merchantRequestId, responseDesc, customerMessage,
                        "Safaricom did not accept the STK push (ResponseCode=" + responseCode + "): " + responseDesc);
            }

            return new StkPushResult(true, checkoutId, merchantRequestId, responseDesc, customerMessage, null);
        } catch (Exception e) {
            log.error("STK Push failed", e);
            return new StkPushResult(false, null, null, null, null,
                    "M-Pesa STK Push failed: " + e.getMessage());
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

    /**
     * Normalizes a Kenyan phone number to Safaricom's 254XXXXXXXXX format.
     * Public so other services (e.g. WalletService, when saving a user's
     * M-Pesa number to their wallet) can store numbers in the same
     * canonical format used for outbound M-Pesa API calls.
     */
    public String normalizePhone(String phone) {
        if (phone == null) return "";
        phone = phone.replaceAll("\\s+", "").replaceAll("[^0-9+]", "");
        if (phone.startsWith("+254")) return phone.substring(1);
        if (phone.startsWith("0"))    return "254" + phone.substring(1);
        if (phone.startsWith("7") || phone.startsWith("1")) return "254" + phone;
        return phone;
    }
}