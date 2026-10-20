package com.premisave.wallet.commercial.rail.paypal;

import com.premisave.wallet.commercial.config.properties.CommercialRailsProperties;
import com.premisave.wallet.commercial.rail.http.ProviderHttp;
import com.premisave.wallet.service.PaypalService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The PayPal REST calls the commercial rail needs. It borrows the access token (and its caching) from the
 * wallet's PaypalService and builds its own requests, so each one can carry the merchant's return addresses
 * and the commercial transaction id. Every request carries a PayPal-Request-Id, so repeating one is safe.
 */
@Component("commercialPaypalApi")
@ConditionalOnProperty(prefix = "commercial.rails.paypal", name = "enabled", havingValue = "true")
public class PaypalApi {

    public record Result(int status, Map<String, Object> body) {
        public boolean ok() { return status >= 200 && status < 300; }

        public boolean serverError() { return status >= 500; }

        public String id() { return PaypalMapper.text(body, "id"); }
    }

    private final PaypalService paypal;
    private final String environment;
    private final JsonMapper json;
    private final ProviderHttp http;

    public PaypalApi(PaypalService paypal, @Value("${paypal.environment:sandbox}") String environment, JsonMapper json,
                     CommercialRailsProperties props) {
        this.paypal = paypal;
        this.environment = environment;
        this.json = json;
        this.http = new ProviderHttp(props.httpTimeoutSeconds());
    }

    public String paypalCustomerId(String ourCustomerId) {
        return paypal.derivePaypalCustomerId(ourCustomerId);
    }

    public Result post(String path, Object body, String requestId) {
        Map<String, String> h = headers();
        h.put("Content-Type", "application/json");
        if (requestId != null) h.put("PayPal-Request-Id", requestId);
        h.put("Prefer", "return=representation");
        return parse(http.post(base() + path, h, body == null ? "{}" : json.writeValueAsString(body)));
    }

    public Result get(String path) {
        return parse(http.get(base() + path, headers()));
    }

    private String base() {
        return "sandbox".equalsIgnoreCase(environment) ? "https://api-m.sandbox.paypal.com" : "https://api-m.paypal.com";
    }

    private Map<String, String> headers() {
        Map<String, String> h = new LinkedHashMap<>();
        String token;
        try {
            token = paypal.getAccessToken();
        } catch (RuntimeException e) {
            throw new ProviderHttp.NotSent("Could not get a PayPal access token: " + e.getMessage());
        }
        h.put("Authorization", "Bearer " + token);
        h.put("Accept", "application/json");
        return h;
    }

    @SuppressWarnings("unchecked")
    private Result parse(ProviderHttp.Reply reply) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (reply.body() != null && !reply.body().isBlank()) {
            try {
                Map<String, Object> parsed = json.readValue(reply.body(), Map.class);
                if (parsed != null) body = parsed;
            } catch (RuntimeException e) {
                // Unreadable body: the status still tells the story.
            }
        }
        return new Result(reply.status(), body);
    }
}
