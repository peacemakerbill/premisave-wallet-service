package com.premisave.wallet.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.premisave.wallet.config.MpesaConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the full lifecycle of the M-Pesa Daraja OAuth access token
 * (https://developer.safaricom.co.ke/apis/Authorization — GET
 * /oauth/v1/generate?grant_type=client_credentials, Basic Auth with
 * consumer key/secret, token valid ~3599s).
 *
 * Runs proactively rather than reactively:
 *  - generates the first token immediately on application startup, so the
 *    very first real M-Pesa call (STK push, B2C, B2Pochi, C2B register-urls,
 *    etc.) never has to wait on an OAuth round-trip or risk a cold cache
 *  - refreshes in the background on a schedule, comfortably ahead of
 *    expiry, so no caller-facing request ever pays that cost or risks
 *    racing an expiry mid-request
 *
 * Every M-Pesa API call in MpesaService goes through getAccessToken() here
 * — this is the single source of truth for the current token.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MpesaTokenService {

    private final MpesaConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient http = new OkHttpClient();

    /** Refresh proactively once less than this much time remains before expiry. */
    private static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);

    private record CachedToken(String token, Instant fetchedAt, Instant expiresAt) {}
    private final AtomicReference<CachedToken> tokenCache = new AtomicReference<>();

    /**
     * Fires once, after the Spring context is fully up — generates the
     * initial token right away rather than waiting for the first real
     * M-Pesa call to trigger it lazily.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUpOnStartup() {
        log.info("M-Pesa token service starting up — generating initial access token...");
        try {
            refreshToken();
        } catch (Exception e) {
            log.error("Initial M-Pesa token generation failed at startup — will retry on the next " +
                    "scheduled background check, or synchronously on the first API call that needs it", e);
        }
    }

    /**
     * Background refresh loop — checks every minute whether the cached
     * token is missing or within REFRESH_MARGIN of expiring, and
     * proactively regenerates it if so. Callers of getAccessToken() should
     * essentially always hit a warm, comfortably-valid cache.
     */
    @Scheduled(fixedDelay = 60_000)
    public void refreshIfNeeded() {
        CachedToken cached = tokenCache.get();
        if (cached != null && Instant.now().isBefore(cached.expiresAt().minus(REFRESH_MARGIN))) {
            log.debug("M-Pesa token still valid until {} — no refresh needed", cached.expiresAt());
            return;
        }

        log.info("M-Pesa access token is missing or nearing expiry — refreshing in the background...");
        try {
            refreshToken();
        } catch (Exception e) {
            log.error("Background M-Pesa token refresh failed — will retry on the next scheduled check", e);
        }
    }

    /**
     * Returns the current cached token. Under normal operation this is
     * always a cheap cache read, since warmUpOnStartup/refreshIfNeeded keep
     * it warm proactively. Falls back to a synchronous refresh only as a
     * last resort — e.g. a request racing application startup before
     * warmUpOnStartup has completed, or the background scheduler somehow
     * falling behind.
     */
    public String getAccessToken() {
        CachedToken cached = tokenCache.get();
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
            return cached.token();
        }
        log.warn("M-Pesa access token requested but cache was empty or expired — fetching synchronously " +
                "(this should be rare; the background refresh should normally keep this warm)");
        return refreshToken();
    }

    private synchronized String refreshToken() {
        // Another thread may have already refreshed while we were waiting
        // on the lock — re-check before making a redundant OAuth call.
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

                Instant now = Instant.now();
                Instant expiresAt = now.plusSeconds(expiresIn);
                tokenCache.set(new CachedToken(token, now, expiresAt));

                log.info("M-Pesa access token generated successfully: expiresInSeconds={} expiresAt={} (attempt {}/3)",
                        expiresIn, expiresAt, attempt);
                return token;
            } catch (Exception e) {
                lastError = new RuntimeException(
                        "Failed to obtain M-Pesa access token (attempt " + attempt + "/3)", e);
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
}