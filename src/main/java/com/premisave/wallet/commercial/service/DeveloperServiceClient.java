package com.premisave.wallet.commercial.service;

import com.premisave.wallet.commercial.config.CommercialProperties;
import com.premisave.wallet.commercial.entity.CommercialEvent;
import com.premisave.wallet.commercial.util.Hashing;
import com.premisave.wallet.commercial.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Calls the developer service, which owns merchant facing delivery.
 *
 * POST {base}/internal/events           forwards one event; any 2xx means accepted
 * POST {base}/internal/callbacks/verify asks it to prove a merchant URL answers a challenge
 *                                       and reply {"verified": true}
 *
 * GET  {base}/internal/admin/developers searches developer accounts (used to find a developerId for a new merchant)
 *
 * Every call carries the internal API key; the event call is also signed.
 */
@Component("commercialDeveloperServiceClient")
public class DeveloperServiceClient {

    private static final Logger log = LoggerFactory.getLogger(DeveloperServiceClient.class);
    private static final Pattern VERIFIED = Pattern.compile("\"verified\"\\s*:\\s*true");
    private static final Pattern RESULT = Pattern.compile("\"result\"\\s*:\\s*\"(ACCEPT|REJECT)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern REASON = Pattern.compile("\"reason\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** The merchant's answer to a validation callback. */
    public record ValidationReply(boolean accepted, String reason) {}

    /**
     * Whether the developer service can be used right now, and the sentence a person can be shown about it.
     * The messages never carry a URL, a key or an exception text; those go to the log only.
     */
    public enum Reach {
        ONLINE("The developer service is online."),
        NOT_CONFIGURED("Developer search is not set up on this system yet. Please contact Premisave support."),
        OFFLINE("The developer service is offline right now, so developers cannot be searched. Please try again in a few minutes."),
        TIMEOUT("The developer service is taking too long to respond. Please try again in a moment."),
        REJECTED("The developer service is online but did not accept this request. Please contact Premisave support."),
        ERROR("The developer service is having trouble at the moment. Please try again shortly.");

        private final String message;

        Reach(String message) { this.message = message; }

        public String message() { return message; }
    }

    /** What the developer service answered to a search: its HTTP status and JSON body (0 and null when it did not answer), and how reachable it was. */
    public record SearchReply(int status, String body, Reach reach) {
        static SearchReply failed(Reach reach) {
            return new SearchReply(0, null, reach);
        }
    }

    private final CommercialProperties.Events config;
    private final HttpClient http;

    @Autowired
    public DeveloperServiceClient(CommercialProperties props) {
        this(props.events());
    }

    public DeveloperServiceClient(CommercialProperties.Events config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.httpTimeoutSeconds()))
                .build();
    }

    public boolean configured() {
        return config.configured();
    }

    /**
     * Searches developer accounts on the developer service. Blank q and status are left out; page and size are sent
     * only when set. The reply carries the developer service's own JSON, which is already the standard envelope, and
     * says whether the service was online, offline, slow, refused the key or failed.
     */
    public SearchReply searchDevelopers(String q, String status, Integer page, Integer size) {
        if (!configured()) return SearchReply.failed(Reach.NOT_CONFIGURED);
        try {
            StringBuilder url = new StringBuilder(base()).append("/internal/admin/developers");
            String sep = "?";
            if (q != null && !q.isBlank()) { url.append(sep).append("q=").append(encode(q.trim())); sep = "&"; }
            if (status != null && !status.isBlank()) { url.append(sep).append("status=").append(encode(status.trim())); sep = "&"; }
            if (page != null) { url.append(sep).append("page=").append(page); sep = "&"; }
            if (size != null) { url.append(sep).append("size=").append(size); }
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url.toString()))
                    .timeout(Duration.ofSeconds(config.httpTimeoutSeconds()))
                    .header("Accept", "application/json");
            if (config.apiKey() != null && !config.apiKey().isBlank()) req.header("X-API-Key", config.apiKey());
            HttpResponse<String> resp = http.send(req.GET().build(), HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            boolean usable = code / 100 == 2 || code == 400;
            Reach reach;
            if (code == 401 || code == 403) reach = Reach.REJECTED;
            else if (!usable || resp.body() == null || resp.body().isBlank()) reach = Reach.ERROR;
            else reach = Reach.ONLINE;
            if (reach != Reach.ONLINE) log.warn("Developer search answered {} ({})", code, reach);
            return new SearchReply(code, resp.body(), reach);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SearchReply.failed(Reach.ERROR);
        } catch (HttpConnectTimeoutException e) {
            log.warn("Developer service did not accept a connection in time: {}", e.toString());
            return SearchReply.failed(Reach.OFFLINE);
        } catch (HttpTimeoutException e) {
            log.warn("Developer service did not answer in time: {}", e.toString());
            return SearchReply.failed(Reach.TIMEOUT);
        } catch (Exception e) {
            log.warn("Developer service could not be reached: {}", e.toString());
            return SearchReply.failed(Reach.OFFLINE);
        }
    }

    /** A one row search that proves the service is up and accepts the key. */
    public Reach check() {
        return searchDevelopers(null, null, 0, 1).reach();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
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

    /**
     * Asks the developer service to put a validation request to the merchant and wait for the answer.
     *
     * POST {base}/internal/validations  with {"registrationId", "url", "deadlineMs", "payload": {...}}
     *
     * The developer service delivers payload to the registered url and replies with the merchant's
     * {"result": "ACCEPT" | "REJECT", "reason": "..."} within the deadline. Returns null when there is no usable
     * answer (not configured, an error, a timeout, an unreadable reply); the caller then applies the
     * registration's onTimeout setting.
     */
    public ValidationReply validate(String registrationId, String url, String payloadJson, int deadlineMs) {
        if (!configured()) return null;
        try {
            String body = "{\"registrationId\":" + Json.quote(registrationId) + ",\"url\":" + Json.quote(url)
                    + ",\"deadlineMs\":" + deadlineMs + ",\"payload\":" + payloadJson + "}";
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(base() + "/internal/validations"))
                    .timeout(Duration.ofMillis(deadlineMs + 1000L))
                    .header("Content-Type", "application/json");
            if (config.apiKey() != null) req.header("X-API-Key", config.apiKey());
            HttpResponse<String> resp = http.send(req.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) return null;
            return parseReply(resp.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("Validation call for registration {} failed: {}", registrationId, e.toString());
            return null;
        }
    }

    static ValidationReply parseReply(String body) {
        if (body == null) return null;
        Matcher result = RESULT.matcher(body);
        if (!result.find()) return null;
        Matcher reason = REASON.matcher(body);
        String why = reason.find() ? reason.group(1).replace("\\\"", "\"").replace("\\\\", "\\") : null;
        return new ValidationReply(result.group(1).equalsIgnoreCase("ACCEPT"), why);
    }

    private String base() {
        String b = config.developerServiceUrl();
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }
}