package com.premisave.wallet.commercial.service;

import com.premisave.wallet.commercial.config.CommercialProperties;
import com.premisave.wallet.commercial.entity.CommercialEvent;
import com.premisave.wallet.commercial.util.Hashing;
import com.premisave.wallet.commercial.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Calls the developer service, which owns merchant facing delivery.
 *
 * POST {base}/internal/events           forwards one event; any 2xx means accepted
 * POST {base}/internal/callbacks/verify asks it to prove a merchant URL answers a challenge
 *                                       and reply {"verified": true}
 *
 * Both calls carry the internal API key; the event call is also signed.
 */
@Component("commercialDeveloperServiceClient")
public class DeveloperServiceClient {

    private static final Logger log = LoggerFactory.getLogger(DeveloperServiceClient.class);
    private static final Pattern VERIFIED = Pattern.compile("\"verified\"\\s*:\\s*true");

    private final CommercialProperties.Events config;
    private final HttpClient http;

    public DeveloperServiceClient(CommercialProperties props) {
        this.config = props.events();
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.httpTimeoutSeconds()))
                .build();
    }

    public boolean configured() {
        return config.configured();
    }

    /** Sends an already serialised event body. Returns null on success, otherwise the reason it failed. */
    public String forward(CommercialEvent event, String jsonBody) {
        try {
            String ts = String.valueOf(Instant.now().getEpochSecond());
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(base() + "/internal/events"))
                    .timeout(Duration.ofSeconds(config.httpTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("X-Premisave-Event-Id", event.getEventId())
                    .header("X-Premisave-Timestamp", ts);
            if (config.apiKey() != null) req.header("X-API-Key", config.apiKey());
            if (config.signingSecret() != null && !config.signingSecret().isBlank()) {
                req.header("X-Premisave-Signature", "sha256=" + Hashing.hmacSha256Hex(config.signingSecret(), ts + "." + jsonBody));
            }
            HttpResponse<String> resp = http.send(req.POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build(),
                    HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() / 100 == 2 ? null : "developer service answered " + resp.statusCode();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** Returns null when the URL answered the challenge, otherwise the reason it did not. */
    public String verifyCallback(String url, String challenge, String registrationId, boolean livemode) {
        if (!configured()) return "callback verification is not configured on this deployment";
        try {
            String body = "{\"url\":" + Json.quote(url) + ",\"challenge\":" + Json.quote(challenge)
                    + ",\"registrationId\":" + Json.quote(registrationId) + ",\"livemode\":" + livemode + "}";
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(base() + "/internal/callbacks/verify"))
                    .timeout(Duration.ofSeconds(Math.max(15, config.httpTimeoutSeconds())))
                    .header("Content-Type", "application/json");
            if (config.apiKey() != null) req.header("X-API-Key", config.apiKey());
            HttpResponse<String> resp = http.send(req.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) return "developer service answered " + resp.statusCode();
            return VERIFIED.matcher(resp.body() == null ? "" : resp.body()).find() ? null : "the URL did not echo the challenge";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        } catch (Exception e) {
            log.warn("Callback verification call failed: {}", e.toString());
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private String base() {
        String b = config.developerServiceUrl();
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }
}
