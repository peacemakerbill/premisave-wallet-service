package com.premisave.wallet.commercial.rail.stripe;

import com.premisave.wallet.commercial.config.CommercialRailsProperties;
import com.premisave.wallet.commercial.rail.http.ProviderHttp;
import com.premisave.wallet.config.StripeConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Stripe REST calls the commercial rail needs, made with the secret key already configured under
 * stripe.secret-key (a test key gives test mode, a live key live mode). Requests are form encoded and carry
 * an Idempotency-Key, so repeating a call after a timeout never charges twice. Every answer that arrives is
 * returned for the caller to judge; only a network failure raises ProviderHttp.NotSent or Unconfirmed.
 */
@Component("commercialStripeApi")
@ConditionalOnProperty(prefix = "commercial.rails.stripe", name = "enabled", havingValue = "true")
public class StripeApi {

    static final String BASE = "https://api.stripe.com";

    public record Result(int status, Map<String, Object> body) {
        public boolean ok() { return status >= 200 && status < 300; }

        public boolean serverError() { return status >= 500; }

        public Map<String, Object> error() { return StripeMapper.map(body, "error"); }

        public String errorCode() { return StripeMapper.text(error(), "code"); }

        public String declineCode() { return StripeMapper.text(error(), "decline_code"); }

        public String message() {
            String m = StripeMapper.text(error(), "message");
            return m.isBlank() ? "Stripe answered " + status : m;
        }

        public String id() { return StripeMapper.text(body, "id"); }
    }

    private final StripeConfig config;
    private final JsonMapper json;
    private final ProviderHttp http;

    public StripeApi(StripeConfig config, JsonMapper json, CommercialRailsProperties props) {
        this.config = config;
        this.json = json;
        this.http = new ProviderHttp(props.httpTimeoutSeconds());
    }

    public Result post(String path, Map<String, String> form, String idempotencyKey) {
        Map<String, String> headers = headers();
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        if (idempotencyKey != null) headers.put("Idempotency-Key", idempotencyKey);
        return parse(http.post(BASE + path, headers, ProviderHttp.form(form)));
    }

    public Result get(String path, Map<String, String> query) {
        String url = BASE + path + (query == null || query.isEmpty() ? "" : "?" + ProviderHttp.form(query));
        return parse(http.get(url, headers()));
    }

    private Map<String, String> headers() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + config.getSecretKey());
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
                // An unreadable body is treated as an empty one; the status still tells the story.
            }
        }
        return new Result(reply.status(), body);
    }
}
