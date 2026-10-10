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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

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
 * SUPPORTS ANY CURRENCY PAIR Frankfurter itself supports — 84 central
 * banks via its v2 API — not a fixed, hand-maintained list. There is
 * deliberately NO hardcoded set of "supported pairs": getRate() below
 * saves a genuinely new pair the first time it's ever requested, and the
 * scheduled refresh then keeps refreshing every pair that has EVER been
 * requested/saved.
 *
 * RESILIENCE: getRate() checks Redis, then MongoDB, and only falls
 * through to a live Frankfurter call if NEITHER has this pair yet. If
 * Frankfurter is genuinely unreachable but a rate was already
 * saved/cached from an earlier successful fetch, that saved rate is used
 * — Frankfurter being down does NOT block a transaction that only needs
 * a pair this system has already seen before. The Redis read itself is
 * wrapped in try/catch: a Redis outage alone must not prevent falling
 * through to the MongoDB check — without that guard, a Redis connection
 * failure would propagate straight out of getRate() and never even reach
 * the DB fallback it's supposed to have.
 *
 * LOGGING: one INFO line per unit of work, never one per pair. A scheduled
 * refresh, a bulk import, and a newly seeded pair each produce exactly one
 * summary line. Anything that went wrong (failed pairs, Redis write
 * failures) is folded into that same line at WARN or ERROR level. Per-pair
 * detail is available at TRACE for developers.
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

    /** Loose sanity check — 3 uppercase letters, matching ISO 4217's own format. Catches an obvious typo before it's saved, WITHOUT maintaining a closed list of "valid" codes. */
    private static final Pattern CURRENCY_CODE_PATTERN = Pattern.compile("^[A-Z]{3}$");

    /** Maximum number of pairs named in a single log line before the rest are summarised as "and N more". */
    private static final int LOG_PAIR_PREVIEW_LIMIT = 10;

    /** Outcome of persisting a batch: how many rates were written and how many Redis cache writes failed along the way. */
    private record PersistResult(int saved, int cacheFailures, String firstCacheError) {}

    /**
     * Reads a rate — Redis first, then MongoDB (populating Redis on a DB
     * hit). If this exact pair has genuinely never been requested or
     * saved before, fetches it live from FxRateService ONCE, saves it,
     * and returns it. base==quote short-circuits to 1 without touching
     * Redis/Mongo/Frankfurter at all.
     *
     * A Redis read failure (connection refused, timeout — Redis itself
     * being down, separate from Frankfurter) is caught and logged, not
     * propagated — falls through to the MongoDB check instead, so a
     * Redis outage alone can't take down conversion for a pair that's
     * already safely stored in MongoDB.
     */
    public BigDecimal getRate(String base, String quote) {
        base = validate(base);
        quote = validate(quote);

        if (base.equals(quote)) {
            return BigDecimal.ONE;
        }

        String key = cacheKey(base, quote);

        try {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                return cached instanceof BigDecimal bd ? bd : new BigDecimal(cached.toString());
            }
        } catch (Exception redisFailure) {
            log.warn("Redis read failed for exchange rate {}->{}, falling back to MongoDB directly. Cause: {}",
                    base, quote, redisFailure.getMessage());
        }

        var saved = exchangeRateRepository.findByBaseCurrencyAndQuoteCurrency(base, quote);
        if (saved.isPresent()) {
            cacheInRedisBestEffort(key, saved.get().getRate());
            return saved.get().getRate();
        }

        log.debug("No saved rate for {}->{} yet, fetching live once", base, quote);
        BigDecimal rate = fxRateService.getRate(base, quote);
        PersistResult result = persistRates(List.of(new ExchangeRateEntry(base, quote, rate)));

        if (result.cacheFailures() > 0) {
            log.warn("New exchange rate pair saved: {}->{} = {} (Redis cache write failed, MongoDB save succeeded). Cause: {}",
                    base, quote, rate, result.firstCacheError());
        } else {
            log.info("New exchange rate pair saved: {}->{} = {}", base, quote, rate);
        }
        return rate;
    }

    /**
     * Shared persistence logic — upserts a set of rates into MongoDB and
     * refreshes their Redis cache entries. Called internally by getRate()
     * (seeding a genuinely new pair), fetchAndSaveAllRates() (bulk
     * Frankfurter import), and scheduledRefresh(). Private — no longer
     * exposed as a manual-entry endpoint (removed on request); every
     * saved rate now originates from Frankfurter itself, never a
     * hand-typed number.
     *
     * Deliberately silent at INFO: each caller owns the single summary line
     * for its own unit of work, so a refresh of many pairs does not turn
     * into many log lines. Per-pair detail is logged at TRACE only.
     */
    private PersistResult persistRates(List<ExchangeRateEntry> entries) {
        int cacheFailures = 0;
        String firstCacheError = null;

        for (ExchangeRateEntry entry : entries) {
            String base = validate(entry.getBaseCurrency());
            String quote = validate(entry.getQuoteCurrency());

            ExchangeRate rate = exchangeRateRepository.findByBaseCurrencyAndQuoteCurrency(base, quote)
                    .orElseGet(ExchangeRate::new);
            rate.setBaseCurrency(base);
            rate.setQuoteCurrency(quote);
            rate.setRate(entry.getRate());
            rate.setUpdatedAt(LocalDateTime.now());
            exchangeRateRepository.save(rate);

            String cacheError = cacheInRedisBestEffort(cacheKey(base, quote), entry.getRate());
            if (cacheError != null) {
                cacheFailures++;
                if (firstCacheError == null) {
                    firstCacheError = cacheError;
                }
            }

            log.trace("Exchange rate persisted: {}->{} = {}", base, quote, entry.getRate());
        }

        return new PersistResult(entries.size(), cacheFailures, firstCacheError);
    }

    public List<ExchangeRate> getAllRates() {
        return exchangeRateRepository.findAll();
    }

    /**
     * Fetches EVERY available rate for the given base currency directly
     * from Frankfurter in ONE call (see FxRateService.getAllRates) and
     * saves all of them — the array comes from Frankfurter itself. This
     * is now the ONLY way rates enter the system directly at an admin's
     * request (the manual typed-payload endpoint was removed) — every
     * other rate arrives either through this, or through the scheduled
     * background refresh.
     *
     * Skips any quote currency that doesn't pass the same 3-letter
     * sanity check as everything else here — defensive; shouldn't happen
     * with a genuine Frankfurter response, but guards against silently
     * saving something malformed if the response shape ever changes.
     *
     * Logs one summary line for the whole import.
     */
    public List<ExchangeRateEntry> fetchAndSaveAllRates(String base) {
        long startedAt = System.nanoTime();
        String validatedBase = validate(base);
        Map<String, BigDecimal> rates = fxRateService.getAllRates(validatedBase);

        List<ExchangeRateEntry> entries = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> entry : rates.entrySet()) {
            String quote = entry.getKey().toUpperCase();
            if (!CURRENCY_CODE_PATTERN.matcher(quote).matches()) {
                skipped.add(entry.getKey());
                continue;
            }
            entries.add(new ExchangeRateEntry(validatedBase, quote, entry.getValue()));
        }

        PersistResult result = persistRates(entries);
        long tookMs = elapsedMillis(startedAt);

        StringBuilder problems = new StringBuilder();
        if (!skipped.isEmpty()) {
            problems.append("; skipped ").append(skipped.size())
                    .append(" unexpected quote code(s): ").append(preview(skipped));
        }
        if (result.cacheFailures() > 0) {
            problems.append("; Redis cache writes failed: ").append(result.cacheFailures())
                    .append(", first cause: ").append(result.firstCacheError());
        }

        if (problems.length() == 0) {
            log.info("Exchange rates imported from Frankfurter: base={}, saved={}, took={} ms",
                    validatedBase, result.saved(), tookMs);
        } else {
            log.warn("Exchange rates imported from Frankfurter with problems: base={}, saved={}, took={} ms{}",
                    validatedBase, result.saved(), tookMs, problems);
        }
        return entries;
    }

    /**
     * Refreshes EVERY pair that has ever been saved or organically
     * requested — no hardcoded list. Up to config.getMaxRetries()
     * attempts per pair on failure; one pair failing doesn't stop the
     * others from refreshing.
     *
     * Produces exactly one summary line per cycle:
     *  - INFO when every pair refreshed and every cache write succeeded.
     *  - WARN when some pairs failed or cache writes failed, naming the
     *    failed pairs and the first cause in the same line.
     *  - ERROR when nothing at all could be refreshed.
     * Per-attempt detail is logged at TRACE.
     */
    @Scheduled(fixedDelayString = "${exchange-rate.refresh-interval-ms:1200000}")
    public void scheduledRefresh() {
        long startedAt = System.nanoTime();
        List<ExchangeRate> existingPairs = exchangeRateRepository.findAll();
        if (existingPairs.isEmpty()) {
            log.info("Exchange rate refresh skipped: no pairs have been saved or requested yet");
            return;
        }

        int maxRetries = config.getMaxRetries();
        int refreshed = 0;
        int retried = 0;
        int cacheFailures = 0;
        String firstCacheError = null;
        List<String> failedPairs = new ArrayList<>();
        String firstFailure = null;

        for (ExchangeRate pair : existingPairs) {
            String base = pair.getBaseCurrency();
            String quote = pair.getQuoteCurrency();
            boolean success = false;
            String lastError = null;

            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                try {
                    BigDecimal rate = fxRateService.getRate(base, quote);
                    PersistResult result = persistRates(List.of(new ExchangeRateEntry(base, quote, rate)));
                    cacheFailures += result.cacheFailures();
                    if (firstCacheError == null) {
                        firstCacheError = result.firstCacheError();
                    }
                    if (attempt > 1) {
                        retried++;
                    }
                    success = true;
                    break;
                } catch (Exception e) {
                    lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    log.trace("Exchange rate refresh attempt {}/{} failed for {}->{}: {}",
                            attempt, maxRetries, base, quote, lastError);
                }
            }

            if (success) {
                refreshed++;
            } else {
                failedPairs.add(base + "->" + quote);
                if (firstFailure == null) {
                    firstFailure = base + "->" + quote + ": " + lastError;
                }
            }
        }

        long tookMs = elapsedMillis(startedAt);
        int failed = failedPairs.size();

        if (failed == 0 && cacheFailures == 0) {
            log.info("Exchange rate refresh complete: pairs={}, refreshed={}, failed=0, retried={}, took={} ms",
                    existingPairs.size(), refreshed, retried, tookMs);
            return;
        }

        StringBuilder problems = new StringBuilder();
        if (failed > 0) {
            problems.append("; failed pairs (last saved rate kept): ").append(preview(failedPairs))
                    .append("; first failure: ").append(firstFailure);
        }
        if (cacheFailures > 0) {
            problems.append("; Redis cache writes failed: ").append(cacheFailures)
                    .append(", first cause: ").append(firstCacheError);
        }

        if (refreshed == 0) {
            log.error("Exchange rate refresh failed for every pair: pairs={}, refreshed=0, failed={}, retried={}, took={} ms{}",
                    existingPairs.size(), failed, retried, tookMs, problems);
        } else {
            log.warn("Exchange rate refresh finished with problems: pairs={}, refreshed={}, failed={}, retried={}, took={} ms{}",
                    existingPairs.size(), refreshed, failed, retried, tookMs, problems);
        }
    }

    /**
     * Best-effort Redis write. Returns null on success, or a short cause
     * description on failure. Never logs by itself: callers aggregate
     * failures into their own summary line so a Redis outage does not
     * produce one warning per pair.
     */
    private String cacheInRedisBestEffort(String key, BigDecimal rate) {
        try {
            redisTemplate.opsForValue().set(key, rate, CACHE_TTL);
            return null;
        } catch (Exception redisFailure) {
            log.trace("Redis write failed for exchange rate cache key={}: {}", key, redisFailure.getMessage());
            return redisFailure.getMessage() != null ? redisFailure.getMessage() : redisFailure.getClass().getSimpleName();
        }
    }

    private String cacheKey(String base, String quote) {
        return CACHE_KEY_PREFIX + base + "-" + quote;
    }

    private static long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    private static String preview(List<String> items) {
        if (items.size() <= LOG_PAIR_PREVIEW_LIMIT) {
            return String.join(", ", items);
        }
        return String.join(", ", items.subList(0, LOG_PAIR_PREVIEW_LIMIT))
                + " and " + (items.size() - LOG_PAIR_PREVIEW_LIMIT) + " more";
    }

    private String validate(String currency) {
        if (currency == null) {
            throw new IllegalArgumentException("Currency code is required");
        }
        String upper = currency.trim().toUpperCase();
        if (!CURRENCY_CODE_PATTERN.matcher(upper).matches()) {
            throw new IllegalArgumentException(
                    "'" + currency + "' doesn't look like a valid currency code — expected 3 uppercase letters "
                            + "(ISO 4217 format, e.g. USD, KES, GBP)");
        }
        return upper;
    }
}