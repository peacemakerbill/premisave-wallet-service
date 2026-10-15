package com.premisave.wallet.commercial.rail.http;

import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A small HTTP client for provider REST calls. It tells the caller apart the two failures that need
 * different reactions: NotSent means the request never reached the provider, so nothing happened;
 * Unconfirmed means it may have been received, so the result is only known by asking later.
 * Any answer that arrives, whatever its status, is returned as a Reply for the caller to judge.
 */
public class ProviderHttp {

    public record Reply(int status, String body) {
        public boolean ok() { return status >= 200 && status < 300; }
        public boolean serverError() { return status >= 500; }
    }

    /** Nothing reached the provider. */
    public static class NotSent extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public NotSent(String message) { super(message); }
    }

    /** The request may have been received. */
    public static class Unconfirmed extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public Unconfirmed(String message) { super(message); }
    }

    private final HttpClient http;
    private final Duration timeout;

    public ProviderHttp(int timeoutSeconds) {
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public Reply get(String url, Map<String, String> headers) {
        return send("GET", url, headers, null);
    }

    public Reply post(String url, Map<String, String> headers, String body) {
        return send("POST", url, headers, body == null ? "" : body);
    }

    public Reply send(String method, String url, Map<String, String> headers, String body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(timeout);
        if (headers != null) headers.forEach(b::header);
        if (body == null) b.method(method, HttpRequest.BodyPublishers.noBody());
        else b.method(method, HttpRequest.BodyPublishers.ofString(body));
        try {
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Reply(r.statusCode(), r.body() == null ? "" : r.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unconfirmed("Interrupted while waiting for the provider");
        } catch (ConnectException | HttpConnectTimeoutException | UnknownHostException e) {
            throw new NotSent("Could not connect to the provider: " + e.getMessage());
        } catch (java.io.IOException e) {
            throw new Unconfirmed("No answer from the provider: " + e.getMessage());
        }
    }

    /** application/x-www-form-urlencoded body, in the map's order. */
    public static String form(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (e.getValue() == null) continue;
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    public static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    public static Map<String, String> headers(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) if (pairs[i + 1] != null) m.put(pairs[i], pairs[i + 1]);
        return m;
    }
}
