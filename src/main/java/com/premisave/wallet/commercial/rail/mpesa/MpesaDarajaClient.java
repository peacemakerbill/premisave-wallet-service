package com.premisave.wallet.commercial.rail.mpesa;

import com.premisave.wallet.commercial.config.CommercialMpesaProperties;
import com.premisave.wallet.commercial.enums.ResultCode;
import com.premisave.wallet.commercial.rail.RailException;
import com.premisave.wallet.config.MpesaConfig;
import com.premisave.wallet.service.MpesaSecurityCredentialService;
import com.premisave.wallet.service.MpesaTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The calls the commercial M-Pesa rail makes to Safaricom Daraja. It reuses the
 * existing credentials, OAuth token cache and security credential encryption,
 * but builds its own requests so each one can carry commercial callback
 * addresses instead of the wallet's.
 *
 * Failures are reported in three ways, because the safe reaction differs:
 * NotSent means nothing reached Safaricom; Rejected means Safaricom refused
 * the request; Unconfirmed means the request may have been received, so the
 * caller must wait for the callback rather than assume it failed.
 */
@Component("commercialMpesaDarajaClient")
@ConditionalOnProperty(prefix = "commercial.mpesa", name = "enabled", havingValue = "true")
public class MpesaDarajaClient {

    private static final Logger log = LoggerFactory.getLogger(MpesaDarajaClient.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");

    public record StkAccepted(String checkoutRequestId, String merchantRequestId, String customerMessage) {}

    /** answered is false while Safaricom is still processing the push. */
    public record StkStatus(boolean answered, String resultCode, String resultDesc) {}

    public record B2cAccepted(String conversationId, String originatorConversationId) {}

    /** Nothing reached Safaricom, so nothing was charged or sent. */
    public static class NotSent extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public NotSent(String message) { super(message); }
    }

    /** The request may have been received; the outcome is only known from the callback. */
    public static class Unconfirmed extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public Unconfirmed(String message) { super(message); }
    }

    private final MpesaConfig config;
    private final MpesaTokenService tokens;
    private final MpesaSecurityCredentialService credentials;
    private final JsonMapper json;
    private final HttpClient http;
    private final Duration timeout;

    public MpesaDarajaClient(MpesaConfig config, MpesaTokenService tokens, MpesaSecurityCredentialService credentials,
                             JsonMapper json, CommercialMpesaProperties props) {
        this.config = config;
        this.tokens = tokens;
        this.credentials = credentials;
        this.json = json;
        this.timeout = Duration.ofSeconds(props.httpTimeoutSeconds());
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /** Sends the customer a payment prompt. */
    public StkAccepted stkPush(String phone, long amountKes, String accountReference, String description, String callbackUrl) {
        String stamp = ZonedDateTime.now(NAIROBI).format(STAMP);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("BusinessShortCode", config.getShortcode());
        body.put("Password", password(stamp));
        body.put("Timestamp", stamp);
        body.put("TransactionType", "CustomerPayBillOnline");
        body.put("Amount", amountKes);
        body.put("PartyA", phone);
        body.put("PartyB", config.getShortcode());
        body.put("PhoneNumber", phone);
        body.put("CallBackURL", callbackUrl);
        body.put("AccountReference", abbreviate(accountReference, 12, "Premisave"));
        body.put("TransactionDesc", abbreviate(description, 13, "Payment"));

        Map<String, Object> reply = post("/mpesa/stkpush/v1/processrequest", body);
        rejectOnError(reply);
        String checkout = text(reply, "CheckoutRequestID");
        if (!"0".equals(text(reply, "ResponseCode")) || checkout.isBlank()) {
            throw rejected("M-Pesa did not accept the payment prompt: " + text(reply, "ResponseDescription"));
        }
        return new StkAccepted(checkout, text(reply, "MerchantRequestID"), text(reply, "CustomerMessage"));
    }

    /** Asks Safaricom what became of a payment prompt whose callback has not arrived. */
    public StkStatus stkQuery(String checkoutRequestId) {
        String stamp = ZonedDateTime.now(NAIROBI).format(STAMP);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("BusinessShortCode", config.getShortcode());
        body.put("Password", password(stamp));
        body.put("Timestamp", stamp);
        body.put("CheckoutRequestID", checkoutRequestId);
        Map<String, Object> reply;
        try {
            reply = post("/mpesa/stkpushquery/v1/query", body);
        } catch (Unconfirmed e) {
            return new StkStatus(false, null, null);
        }
        // While the push is still being processed Safaricom answers with an errorCode, not a result.
        if (!text(reply, "errorCode").isBlank() || text(reply, "ResultCode").isBlank()) {
            return new StkStatus(false, null, null);
        }
        return new StkStatus(true, text(reply, "ResultCode"), text(reply, "ResultDesc"));
    }

    /** Sends money to a customer's phone. The conversation id is the transaction id, so a repeat can never double pay. */
    public B2cAccepted b2c(String phone, long amountKes, String remarks, String originatorConversationId,
                           String resultUrl, String timeoutUrl) {
        MpesaConfig.B2c b2c = config.getB2c();
        String credential;
        try {
            credential = credentials.encrypt(b2c.getInitiatorPassword(), config.getCertificatePath());
        } catch (RuntimeException e) {
            throw new NotSent("The M-Pesa initiator credential could not be prepared: " + e.getMessage());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("OriginatorConversationID", originatorConversationId);
        body.put("InitiatorName", b2c.getInitiatorName());
        body.put("SecurityCredential", credential);
        body.put("CommandID", b2c.getCommandId() != null ? b2c.getCommandId() : "BusinessPayment");
        body.put("Amount", amountKes);
        body.put("PartyA", b2c.getShortcode() != null ? b2c.getShortcode() : config.getShortcode());
        body.put("PartyB", phone);
        body.put("Remarks", abbreviate(remarks, 100, "Payout"));
        body.put("QueueTimeOutURL", timeoutUrl);
        body.put("ResultURL", resultUrl);
        body.put("Occassion", "Payout");

        Map<String, Object> reply = post("/mpesa/b2c/v3/paymentrequest", body);
        rejectOnError(reply);
        if (!"0".equals(text(reply, "ResponseCode"))) {
            throw rejected("M-Pesa did not accept the payout: " + text(reply, "ResponseDescription"));
        }
        return new B2cAccepted(text(reply, "ConversationID"), text(reply, "OriginatorConversationID"));
    }

    // ---------------------------------------------------------------- plumbing

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Map<String, Object> body) {
        String token;
        try {
            token = tokens.getAccessToken();
        } catch (RuntimeException e) {
            throw new NotSent("Could not get an M-Pesa access token: " + e.getMessage());
        }
        if (token == null || token.isBlank()) {
            throw new NotSent("Could not get an M-Pesa access token");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.baseUrl() + path))
                .timeout(timeout)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unconfirmed("Interrupted while waiting for M-Pesa");
        } catch (ConnectException | HttpConnectTimeoutException e) {
            throw new NotSent("Could not connect to M-Pesa: " + e.getMessage());
        } catch (java.io.IOException e) {
            throw new Unconfirmed("No answer from M-Pesa: " + e.getMessage());
        }
        String raw = response.body() == null ? "" : response.body();
        log.debug("Daraja {} answered {}: {}", path, response.statusCode(), raw);
        if (response.statusCode() >= 500 && raw.isBlank()) {
            throw new Unconfirmed("M-Pesa answered " + response.statusCode());
        }
        try {
            Map<String, Object> parsed = raw.isBlank() ? new LinkedHashMap<>() : json.readValue(raw, Map.class);
            return parsed == null ? new LinkedHashMap<>() : parsed;
        } catch (RuntimeException e) {
            throw new Unconfirmed("M-Pesa answered with something unreadable (HTTP " + response.statusCode() + ")");
        }
    }

    private void rejectOnError(Map<String, Object> reply) {
        String errorCode = text(reply, "errorCode");
        if (!errorCode.isBlank()) {
            String message = text(reply, "errorMessage");
            log.warn("Daraja refused a request: {} {}", errorCode, message);
            throw rejected("M-Pesa refused the request: " + errorCode + (message.isBlank() ? "" : " " + message));
        }
    }

    private RailException rejected(String message) {
        return new RailException(RailException.Kind.REJECTED, ResultCode.PROVIDER_ERROR, message);
    }

    private String password(String stamp) {
        return Base64.getEncoder().encodeToString(
                (config.getShortcode() + config.getPasskey() + stamp).getBytes(StandardCharsets.UTF_8));
    }

    private static String text(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    static String abbreviate(String value, int max, String fallback) {
        String v = value == null || value.isBlank() ? fallback : value.trim();
        return v.length() <= max ? v : v.substring(0, max);
    }
}
