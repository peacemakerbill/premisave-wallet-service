package com.premisave.wallet.commercial.onboarding;

import com.premisave.wallet.commercial.config.CommercialOnboardingProperties;
import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.util.Ids;
import com.premisave.wallet.commercial.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Stores onboarding documents in a Google Drive folder using a service account. No Google client library is
 * needed: the access token comes from a signed JWT (see GoogleServiceAccount) and files move over the plain
 * Drive v3 REST API. The scope is drive.file, so the service account can only touch files it created itself.
 */
public class GoogleDriveStorage implements DocumentStorage {

    private static final Logger log = LoggerFactory.getLogger(GoogleDriveStorage.class);
    private static final String SCOPE = "https://www.googleapis.com/auth/drive.file";
    private static final Pattern FILE_ID = Pattern.compile("^[A-Za-z0-9_-]{10,100}$");

    private final CommercialOnboardingProperties.Drive cfg;
    private final JsonMapper json;
    private final HttpClient http;

    private String clientEmail;
    private PrivateKey privateKey;
    private String token;
    private Instant tokenExpiry = Instant.EPOCH;

    public GoogleDriveStorage(CommercialOnboardingProperties.Drive cfg, JsonMapper json) {
        this.cfg = cfg;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(cfg.httpTimeoutSeconds())).build();
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public StoredFile upload(String fileName, String contentType, byte[] bytes) {
        String boundary = "prm" + Ids.next("b").substring(2);
        String metadata = "{\"name\":" + Json.quote(fileName) + ",\"parents\":[" + Json.quote(cfg.folderId()) + "]}";
        byte[] body = DriveMultipart.body(boundary, metadata, contentType, bytes);
        HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.uploadBase()
                        + "/files?uploadType=multipart&supportsAllDrives=true&fields=id,webViewLink"))
                .timeout(Duration.ofSeconds(cfg.httpTimeoutSeconds()))
                .header("Authorization", "Bearer " + accessToken())
                .header("Content-Type", DriveMultipart.contentType(boundary))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        HttpResponse<String> res = send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), "upload");
        if (res.statusCode() / 100 != 2) throw failed("upload", res.statusCode(), res.body());
        Map<?, ?> parsed = json.readValue(res.body(), Map.class);
        Object id = parsed.get("id");
        if (id == null) throw failed("upload", res.statusCode(), "no file id in the reply");
        Object link = parsed.get("webViewLink");
        return new StoredFile(id.toString(), link == null ? null : link.toString());
    }

    @Override
    public byte[] download(String fileId) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.apiBase() + "/files/" + safeId(fileId)
                        + "?alt=media&supportsAllDrives=true"))
                .timeout(Duration.ofSeconds(cfg.httpTimeoutSeconds()))
                .header("Authorization", "Bearer " + accessToken()).GET().build();
        HttpResponse<byte[]> res = send(req, HttpResponse.BodyHandlers.ofByteArray(), "download");
        if (res.statusCode() == 404) throw new CommercialException(ErrorCode.NOT_FOUND, "The stored document could not be found");
        if (res.statusCode() / 100 != 2) throw failed("download", res.statusCode(), "");
        return res.body();
    }

    @Override
    public void delete(String fileId) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.apiBase() + "/files/" + safeId(fileId)
                        + "?supportsAllDrives=true"))
                .timeout(Duration.ofSeconds(cfg.httpTimeoutSeconds()))
                .header("Authorization", "Bearer " + accessToken()).DELETE().build();
        HttpResponse<String> res = send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), "delete");
        if (res.statusCode() == 404 || res.statusCode() / 100 == 2) return;
        throw failed("delete", res.statusCode(), res.body());
    }

    private synchronized String accessToken() {
        Instant now = Instant.now();
        if (token != null && now.isBefore(tokenExpiry.minusSeconds(60))) return token;
        loadKey();
        String assertion = GoogleServiceAccount.assertion(clientEmail, privateKey, SCOPE, cfg.tokenUrl(), now);
        String form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
                + "&assertion=" + URLEncoder.encode(assertion, StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.tokenUrl()))
                .timeout(Duration.ofSeconds(cfg.httpTimeoutSeconds()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        HttpResponse<String> res = send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), "token");
        if (res.statusCode() / 100 != 2) throw failed("token", res.statusCode(), res.body());
        Map<?, ?> parsed = json.readValue(res.body(), Map.class);
        Object access = parsed.get("access_token");
        if (access == null) throw failed("token", res.statusCode(), "no access token in the reply");
        long ttl = parsed.get("expires_in") instanceof Number n ? n.longValue() : 3300;
        token = access.toString();
        tokenExpiry = now.plusSeconds(ttl);
        return token;
    }

    private void loadKey() {
        if (privateKey != null) return;
        try {
            String raw = cfg.serviceAccountJson() != null && !cfg.serviceAccountJson().isBlank()
                    ? cfg.serviceAccountJson()
                    : Files.readString(Path.of(cfg.serviceAccountJsonPath()), StandardCharsets.UTF_8);
            Map<?, ?> parsed = json.readValue(raw, Map.class);
            Object email = parsed.get("client_email");
            Object key = parsed.get("private_key");
            if (email == null || key == null) {
                throw new IllegalStateException("client_email or private_key is missing");
            }
            PrivateKey parsedKey = GoogleServiceAccount.parsePrivateKey(key.toString());
            clientEmail = email.toString();
            privateKey = parsedKey;
        } catch (IOException | RuntimeException e) {
            log.error("Google Drive service account key could not be loaded: {}", e.getMessage());
            throw new CommercialException(ErrorCode.PROVIDER_UNAVAILABLE, "Document storage is not available right now");
        }
    }

    private <T> HttpResponse<T> send(HttpRequest req, HttpResponse.BodyHandler<T> handler, String step) {
        try {
            return http.send(req, handler);
        } catch (IOException e) {
            log.warn("Google Drive {} failed: {}", step, e.getMessage());
            throw new CommercialException(ErrorCode.PROVIDER_UNAVAILABLE, "Document storage is not available right now");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CommercialException(ErrorCode.PROVIDER_UNAVAILABLE, "Document storage was interrupted");
        }
    }

    private static CommercialException failed(String step, int status, String detail) {
        String shortDetail = detail == null ? "" : detail.substring(0, Math.min(detail.length(), 300));
        log.warn("Google Drive {} answered {}: {}", step, status, shortDetail);
        return new CommercialException(ErrorCode.PROVIDER_UNAVAILABLE, "Document storage is not available right now");
    }

    private static String safeId(String fileId) {
        if (fileId == null || !FILE_ID.matcher(fileId).matches()) {
            throw new CommercialException(ErrorCode.NOT_FOUND, "The stored document could not be found");
        }
        return fileId;
    }
}
