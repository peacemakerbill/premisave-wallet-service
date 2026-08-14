package com.premisave.wallet.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.premisave.wallet.config.NowPaymentsConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * NOWPayments API integration layer — raw HTTP calls, no official Java SDK
 * exists (their docs list Python/Node/PHP connectors, not Java), so this
 * follows the same hand-built OkHttp pattern already used for M-Pesa in
 * this codebase rather than a polished client library like stripe-java.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NowPaymentsService {

    private final NowPaymentsConfig config;

    private final OkHttpClient httpClient = new OkHttpClient();

    // USE_BIG_DECIMAL_FOR_FLOATS avoids float-rounding artifacts on amount
    // fields (price_amount, actually_paid, etc.) when parsing IPN bodies for
    // signature verification — see verifyIpnSignature's javadoc.
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true);

    public record CreatePaymentResult(boolean success, String paymentId, String payAddress, String payAmount,
                                       String payCurrency, String paymentStatus, String message) {
    }

    public record PaymentStatusResult(boolean success, String paymentStatus, String actuallyPaid, String message) {
    }

    /**
     * Creates a crypto payment — POST /v1/payment. priceAmount/priceCurrency
     * is what you're charging in YOUR currency (KES here); payCurrency is
     * the crypto the customer will actually send (e.g. "usdttrc20", "btc").
     * NOWPayments quotes the crypto-equivalent amount and a one-time deposit
     * address in the response.
     *
     * sandboxCase is sandbox-only — set to "finished", "failed",
     * "partially_paid", etc. to make NOWPayments immediately simulate that
     * outcome (via an IPN callback) without ever needing real crypto. Null
     * or blank in production, where it's simply not sent.
     */
    public CreatePaymentResult createPayment(BigDecimal priceAmount, String priceCurrency, String payCurrency,
                                              String orderId, String orderDescription, String sandboxCase) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("price_amount", priceAmount);
            body.put("price_currency", priceCurrency.toLowerCase());
            body.put("pay_currency", payCurrency.toLowerCase());
            body.put("order_id", orderId);
            if (orderDescription != null) {
                body.put("order_description", orderDescription);
            }
            body.put("ipn_callback_url", config.getCallbackUrl());
            if (sandboxCase != null && !sandboxCase.isBlank()) {
                body.put("case", sandboxCase);
            }

            String json = objectMapper.writeValueAsString(body);
            RequestBody requestBody = RequestBody.create(json, MediaType.parse("application/json"));
            Request request = new Request.Builder()
                    .url(config.getBaseUrl() + "/v1/payment")
                    .addHeader("x-api-key", config.getApiKey())
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.warn("NOWPayments createPayment failed: status={} body={}", response.code(), responseBody);
                    return new CreatePaymentResult(false, null, null, null, null, null,
                            "NOWPayments createPayment failed (" + response.code() + "): " + responseBody);
                }

                JsonNode node = objectMapper.readTree(responseBody);
                CreatePaymentResult result = new CreatePaymentResult(true,
                        node.path("payment_id").asText(null),
                        node.path("pay_address").asText(null),
                        node.path("pay_amount").asText(null),
                        node.path("pay_currency").asText(null),
                        node.path("payment_status").asText(null),
                        "Payment created");
                log.info("NOWPayments payment created: paymentId={} orderId={} payAddress={} payAmount={} {} status={}",
                        result.paymentId(), orderId, result.payAddress(), result.payAmount(), result.payCurrency(), result.paymentStatus());
                return result;
            }
        } catch (Exception e) {
            log.error("NOWPayments createPayment error: {}", e.getMessage(), e);
            return new CreatePaymentResult(false, null, null, null, null, null,
                    "NOWPayments createPayment error: " + e.getMessage());
        }
    }

    /** GET /v1/payment/{id} — manual status check, mainly for reconciliation; the IPN webhook is the primary path. */
    public PaymentStatusResult getPaymentStatus(String paymentId) {
        try {
            Request request = new Request.Builder()
                    .url(config.getBaseUrl() + "/v1/payment/" + paymentId)
                    .addHeader("x-api-key", config.getApiKey())
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.warn("NOWPayments getPaymentStatus failed: status={} body={}", response.code(), responseBody);
                    return new PaymentStatusResult(false, null, null,
                            "NOWPayments getPaymentStatus failed (" + response.code() + "): " + responseBody);
                }

                JsonNode node = objectMapper.readTree(responseBody);
                return new PaymentStatusResult(true,
                        node.path("payment_status").asText(null),
                        node.path("actually_paid").asText(null),
                        "OK");
            }
        } catch (Exception e) {
            log.error("NOWPayments getPaymentStatus error: {}", e.getMessage(), e);
            return new PaymentStatusResult(false, null, null, "NOWPayments getPaymentStatus error: " + e.getMessage());
        }
    }

    /**
     * Verifies the "x-nowpayments-sig" header on an incoming IPN webhook.
     *
     * NOWPayments signs HMAC-SHA512(ipnSecret, canonicalJson) where
     * canonicalJson is the recursively-key-sorted, compact JSON
     * serialization of the raw callback body — confirmed against
     * NOWPayments' own official Node.js SDK
     * (github.com/NowPaymentsIO/nowpayments-sdk-nodejs) and their official
     * WooCommerce plugin's PHP implementation
     * (github.com/NowPaymentsIO/nowpayments-payment-gateway-for-woocommerce).
     * Both simply re-serialize each language's own sorted-map JSON
     * representation and trust it round-trips consistently with what
     * NOWPayments computed server-side — this Java implementation does the
     * same (TreeMap for recursive key sorting at every nesting level,
     * Jackson for serialization, USE_BIG_DECIMAL_FOR_FLOATS above to avoid
     * float-rounding artifacts on amount fields).
     *
     * This is the same class of best-effort cross-language assumption
     * every official example makes, not an ironclad guarantee — if a
     * genuine IPN call ever fails verification, the first thing to check
     * is whether some numeric field's textual representation differs
     * between what NOWPayments sent and what Jackson reserializes it as
     * (e.g. "50.00" vs "50.0" vs "50"). This logs a warning rather than
     * silently dropping the event, so a mismatch is visible if it happens.
     */
    public boolean verifyIpnSignature(String rawBody, String signatureHeader) {
        if (signatureHeader == null || signatureHeader.isBlank()) {
            log.warn("NOWPayments IPN missing x-nowpayments-sig header — rejecting");
            return false;
        }
        try {
            Object parsed = objectMapper.readValue(rawBody, Object.class);
            Object sorted = deepSortKeys(parsed);
            String canonicalJson = objectMapper.writeValueAsString(sorted);

            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(config.getIpnSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            byte[] hash = mac.doFinal(canonicalJson.getBytes(StandardCharsets.UTF_8));

            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }

            boolean matches = hex.toString().equals(signatureHeader);
            if (!matches) {
                log.warn("NOWPayments IPN signature mismatch — check NOWPAYMENTS_IPN_SECRET matches the current "
                        + "sandbox/production dashboard value (these are NOT interchangeable between environments)");
            }
            return matches;
        } catch (Exception e) {
            log.error("Failed to verify NOWPayments IPN signature: {}", e.getMessage(), e);
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private Object deepSortKeys(Object node) {
        if (node instanceof Map) {
            Map<String, Object> sorted = new TreeMap<>();
            ((Map<String, Object>) node).forEach((k, v) -> sorted.put(k, deepSortKeys(v)));
            return sorted;
        } else if (node instanceof List) {
            List<Object> list = (List<Object>) node;
            List<Object> result = new ArrayList<>(list.size());
            for (Object item : list) {
                result.add(deepSortKeys(item));
            }
            return result;
        }
        return node;
    }
}