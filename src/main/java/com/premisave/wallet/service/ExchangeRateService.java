package com.premisave.wallet.service;

import com.premisave.wallet.config.ExchangeRateConfig;
import com.premisave.wallet.dto.ExchangeRateEntry;
import com.premisave.wallet.entity.ExchangeRate;
import com.premisave.wallet.repository.ExchangeRateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Caching layer IN FRONT OF FxRateService, not a replacement for it.
 * FxRateService.getRate() makes a real, blocking HTTP call to Frankfurter
 * on every single invocation — no proactive caching there at all, only a
 * 2-minute last-known-good fallback used AFTER a failure. Calling that
 * directly from every M-Pesa/Flutterwave transaction would mean every
 * single money-movement operation waits on a live third-party HTTP call,
 * with a real outage turning into a hard failure across every gateway
 * that needs conversion.
 *
 * getRate() below NEVER calls FxRateService directly — it only reads
 * from Redis, then MongoDB. FxRateService is called ONLY from
 * scheduledRefresh(), which runs on its own interval and writes the
 * result into both MongoDB (the durable source of truth) and Redis (the
 * fast read path). If Frankfurter is down, MongoDB and Redis both keep
 * serving the last successfully-fetched rate — genuinely resilient to an
 * FX outage, not just degraded.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeRateService {

    private final ExchangeRateRepository exchangeRateRepository;
    private final FxRateService fxRateService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ExchangeRateConfig config;

    private static final String CACHE_KEY_PREFIX = "exchange-rate:";

    /** Redis TTL — separate from the refresh interval on purpose: even if a refresh cycle is delayed, cached entries don't expire and silently fall through to a DB read on every single call. */
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);

    /** The full set of directional pairs this system actually needs — fixed since Currency only has KES/USD/EUR. */
    private static final List<String[]> SUPPORTED_PAIRS = List.of(
            new String[]{"USD", "KES"},
            new String[]{"KES", "USD"},
            new String[]{"USD", "EUR"},
            new String[]{"EUR", "USD"},
            new String[]{"KES", "EUR"},
            new String[]{"EUR", "KES"}
    );

    /**
     * Reads a rate — Redis first, then MongoDB (populating Redis on a DB
     * hit so the next read is fast). Throws clearly if the pair genuinely
     * has no saved rate yet (e.g. before the very first refresh has ever
     * run, or a manual save) — this is a real gap the caller needs to
     * know about, not something to silently paper over with a made-up
     * default.
     */
    public BigDecimal getRate(String base, String quote) {
        String key = cacheKey(base, quote);

        Object cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            return cached instanceof BigDecimal bd ? bd : new BigDecimal(cached.toString());
        }

        ExchangeRate rate = exchangeRateRepository
                .findByBaseCurrencyAndQuoteCurrency(base.toUpperCase(), quote.toUpperCase())
                .orElseThrow(() -> new IllegalStateException(
                        "No saved exchange rate for " + base + "->" + quote
                                + " — the refresh job may not have run yet, or this pair was never saved manually."));

        redisTemplate.opsForValue().set(key, rate.getRate(), CACHE_TTL);
        return rate.getRate();
    }

    /**
     * Backing logic for the admin add/update endpoint — accepts an array
     * so multiple pairs can be saved or updated in a single call. Upserts:
     * an existing pair is updated in place, a new one is inserted.
     * Refreshes the Redis cache for each entry immediately, so a manually
     * saved rate is available on the very next read, not just after the
     * next scheduled refresh cycle.
     */
    public void saveOrUpdateRates(List<ExchangeRateEntry> entries) {
        for (ExchangeRateEntry entry : entries) {
            String base = entry.getBaseCurrency().toUpperCase();
            String quote = entry.getQuoteCurrency().toUpperCase();

            ExchangeRate rate = exchangeRateRepository.findByBaseCurrencyAndQuoteCurrency(base, quote)
                    .orElseGet(ExchangeRate::new);
            rate.setBaseCurrency(base);
            rate.setQuoteCurrency(quote);
            rate.setRate(entry.getRate());
            rate.setUpdatedAt(LocalDateTime.now());
            exchangeRateRepository.save(rate);

            redisTemplate.opsForValue().set(cacheKey(base, quote), entry.getRate(), CACHE_TTL);
        }
        log.info("Saved/updated {} exchange rate(s)", entries.size());
    }

    public List<ExchangeRate> getAllRates() {
        return exchangeRateRepository.findAll();
    }

    /**
     * Refreshes every supported pair from the live Frankfurter API, with
     * up to config.getMaxRetries() attempts per pair on failure — a
     * single dropped request shouldn't leave a rate stale for a whole
     * refresh cycle. One pair failing all its retries doesn't stop the
     * others from refreshing; each pair succeeds or fails independently.
     *
     * Interval is configurable via application.yml
     * (exchange-rate.refresh-interval-ms), defaulting to 20 minutes
     * (1,200,000 ms) if unset.
     */
    @Scheduled(fixedDelayString = "${exchange-rate.refresh-interval-ms:1200000}")
    public void scheduledRefresh() {
        log.info("Starting scheduled exchange rate refresh for {} pair(s)", SUPPORTED_PAIRS.size());
        int refreshed = 0, failed = 0;

        for (String[] pair : SUPPORTED_PAIRS) {
            String base = pair[0];
            String quote = pair[1];
            boolean success = false;

            for (int attempt = 1; attempt <= config.getMaxRetries(); attempt++) {
                try {
                    BigDecimal rate = fxRateService.getRate(base, quote);
                    saveOrUpdateRates(List.of(new ExchangeRateEntry(base, quote, rate)));
                    success = true;
                    break;
                } catch (Exception e) {
                    log.warn("Exchange rate refresh failed for {}->{} (attempt {}/{}): {}",
                            base, quote, attempt, config.getMaxRetries(), e.getMessage());
                }
            }

            if (success) {
                refreshed++;
            } else {
                failed++;
                log.error("Exchange rate refresh permanently failed for {}->{} after {} attempts — " +
                        "keeping the last saved rate (if any) in both MongoDB and Redis; this pair may serve " +
                        "a stale value until the next refresh cycle", base, quote, config.getMaxRetries());
            }
        }

        log.info("Exchange rate refresh complete: {} refreshed, {} failed", refreshed, failed);
    }

    private String cacheKey(String base, String quote) {
        return CACHE_KEY_PREFIX + base.toUpperCase() + "-" + quote.toUpperCase();
    }
}