package com.premisave.wallet.service;

import com.premisave.wallet.config.ExchangeRateConfig;
import com.premisave.wallet.dto.ExchangeRateEntry;
import com.premisave.wallet.entity.ExchangeRate;
import com.premisave.wallet.repository.ExchangeRateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * REFRESH: on every application start, and then every
 * exchange-rate.refresh-interval-ms (default 10 minutes), the full set of
 * rates for the configured base currency (default USD) is fetched from
 * Frankfurter in one bulk call and written to MongoDB and Redis,
 * overwriting whatever was stored before, whether or not any pair was ever
 * requested. Pairs that were saved earlier but are not covered by that bulk
 * call (for example KES->USD, which the wallet needs for conversion to its
 * USD ground truth) are then refreshed individually.
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

    /** Outcome of one bulk Frankfurter import: the entries written, any quote codes skipped as malformed, and the persistence result. */
    private record BulkImport(List<ExchangeRateEntry> entries, List<String> skipped, PersistResult persisted) {}

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
     * saves all of them, overwriting any existing row for the same pair.
     * Used by the admin endpoint; the startup and scheduled refresh use the
     * same import internally (see runRefreshCycle).
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
        BulkImport bulk = importBulk(validatedBase);
        long tookMs = elapsedMillis(startedAt);

        StringBuilder problems = new StringBuilder();
        appendBulkProblems(problems, bulk);

        if (problems.length() == 0) {
            log.info("Exchange rates imported from Frankfurter: base={}, saved={}, took={} ms",
                    validatedBase, bulk.persisted().saved(), tookMs);
        } else {
            log.warn("Exchange rates imported from Frankfurter with problems: base={}, saved={}, took={} ms{}",
                    validatedBase, bulk.persisted().saved(), tookMs, problems);
        }

        Map<String, BigDecimal> importedRates = new LinkedHashMap<>();
        for (ExchangeRateEntry entry : bulk.entries()) {
            importedRates.put(entry.getBaseCurrency() + "->" + entry.getQuoteCurrency(), entry.getRate());
        }
        logSavedRates("import", importedRates);
        return bulk.entries();
    }

    /**
     * Logs every rate written in a batch on ONE line at DEBUG, for example
     * "USD->KES=129.37, USD->EUR=0.86, ...". Kept off the INFO summary so
     * operators get a short line and developers can still see exact values.
     */
    private void logSavedRates(String label, Map<String, BigDecimal> rates) {
        if (rates.isEmpty() || !log.isDebugEnabled()) {
            return;
        }
        StringBuilder line = new StringBuilder();
        for (Map.Entry<String, BigDecimal> rate : rates.entrySet()) {
            if (line.length() > 0) {
                line.append(", ");
            }
            line.append(rate.getKey()).append('=').append(rate.getValue().stripTrailingZeros().toPlainString());
        }
        log.debug("Exchange rates saved ({}): {}", label, line);
    }

    /** Fetches and persists every Frankfurter rate for one base. Silent: callers own the log line. */
    private BulkImport importBulk(String validatedBase) {
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

        return new BulkImport(entries, skipped, persistRates(entries));
    }

    private void appendBulkProblems(StringBuilder problems, BulkImport bulk) {
        if (!bulk.skipped().isEmpty()) {
            problems.append("; skipped ").append(bulk.skipped().size())
                    .append(" unexpected quote code(s): ").append(preview(bulk.skipped()));
        }
        if (bulk.persisted().cacheFailures() > 0) {
            problems.append("; Redis cache writes failed: ").append(bulk.persisted().cacheFailures())
                    .append(", first cause: ").append(bulk.persisted().firstCacheError());
        }
    }

    /**
     * Runs one refresh cycle as soon as the application is ready, so the
     * database holds current rates from the first minute regardless of
     * what was stored before. Failures are logged, never propagated: a
     * Frankfurter outage at boot must not stop the application from
     * starting, and the stored rates from the previous run stay in use.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void refreshOnStartup() {
        try {
            runRefreshCycle("startup");
        } catch (Exception e) {
            log.error("Exchange rate refresh on startup failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Repeats the refresh every exchange-rate.refresh-interval-ms. The
     * first scheduled run waits one full interval because refreshOnStartup
     * has already covered the moment of boot.
     */
    @Scheduled(
            initialDelayString = "${exchange-rate.refresh-interval-ms:600000}",
            fixedDelayString = "${exchange-rate.refresh-interval-ms:600000}")
    public void scheduledRefresh() {
        runRefreshCycle("scheduled");
    }

    /**
     * One refresh cycle:
     *  1. Bulk import of every rate for the configured base currency,
     *     overwriting stored rows (up to config.getMaxRetries() attempts).
     *  2. Individual refresh of every other saved pair the bulk call did
     *     not cover, with the same retry limit per pair. One pair failing
     *     does not stop the others. Skipped when step 1 failed on every
     *     attempt, since Frankfurter is then almost certainly unreachable
     *     and retrying each pair would only stall the cycle on timeouts.
     *
     * Produces exactly one summary line per cycle:
     *  - INFO when everything succeeded.
     *  - WARN when something failed, naming the failed pairs and the first
     *    cause in the same line.
     *  - ERROR when nothing at all could be saved.
     * Per-attempt detail is logged at TRACE.
     */
    private void runRefreshCycle(String trigger) {
        long startedAt = System.nanoTime();
        String baseCurrency = validate(config.getBaseCurrency());
        int maxRetries = config.getMaxRetries();

        int retried = 0;
        int cacheFailures = 0;
        String firstCacheError = null;

        BulkImport bulk = null;
        String bulkError = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                bulk = importBulk(baseCurrency);
                if (attempt > 1) {
                    retried++;
                }
                break;
            } catch (Exception e) {
                bulkError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                log.trace("Bulk exchange rate import attempt {}/{} failed for base={}: {}",
                        attempt, maxRetries, baseCurrency, bulkError);
            }
        }

        Set<String> covered = new HashSet<>();
        Map<String, BigDecimal> savedRates = new LinkedHashMap<>();
        int bulkSaved = 0;
        if (bulk != null) {
            bulkSaved = bulk.persisted().saved();
            cacheFailures += bulk.persisted().cacheFailures();
            firstCacheError = bulk.persisted().firstCacheError();
            for (ExchangeRateEntry entry : bulk.entries()) {
                String pairKey = entry.getBaseCurrency() + "->" + entry.getQuoteCurrency();
                covered.add(pairKey);
                savedRates.put(pairKey, entry.getRate());
            }
        }

        int individuallyRefreshed = 0;
        List<String> failedPairs = new ArrayList<>();
        String firstFailure = null;

        if (bulk != null) {
            List<ExchangeRate> otherPairs = exchangeRateRepository.findAll().stream()
                    .filter(pair -> !covered.contains(pair.getBaseCurrency() + "->" + pair.getQuoteCurrency()))
                    .toList();

            for (ExchangeRate pair : otherPairs) {
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
                        savedRates.put(base + "->" + quote, rate);
                        success = true;
                        break;
                    } catch (Exception e) {
                        lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                        log.trace("Exchange rate refresh attempt {}/{} failed for {}->{}: {}",
                                attempt, maxRetries, base, quote, lastError);
                    }
                }

                if (success) {
                    individuallyRefreshed++;
                } else {
                    failedPairs.add(base + "->" + quote);
                    if (firstFailure == null) {
                        firstFailure = base + "->" + quote + ": " + lastError;
                    }
                }
            }
        }

        long tookMs = elapsedMillis(startedAt);
        int saved = bulkSaved + individuallyRefreshed;
        boolean bulkFailed = bulk == null;
        boolean hasProblems = bulkFailed || !failedPairs.isEmpty() || cacheFailures > 0
                || (bulk != null && !bulk.skipped().isEmpty());

        if (!hasProblems) {
            log.info("Exchange rate refresh complete ({}): base={}, saved={} (bulk={}, individual={}), failed=0, retried={}, took={} ms",
                    trigger, baseCurrency, saved, bulkSaved, individuallyRefreshed, retried, tookMs);
            logSavedRates(trigger, savedRates);
            return;
        }

        StringBuilder problems = new StringBuilder();
        if (bulkFailed) {
            problems.append("; bulk import of ").append(baseCurrency).append(" failed after ").append(maxRetries)
                    .append(" attempt(s): ").append(bulkError)
                    .append("; individual pairs not refreshed, last saved rates kept");
        } else if (!bulk.skipped().isEmpty()) {
            problems.append("; skipped ").append(bulk.skipped().size())
                    .append(" unexpected quote code(s): ").append(preview(bulk.skipped()));
        }
        if (cacheFailures > 0) {
            problems.append("; Redis cache writes failed: ").append(cacheFailures)
                    .append(", first cause: ").append(firstCacheError);
        }
        if (!failedPairs.isEmpty()) {
            problems.append("; failed pairs (last saved rate kept): ").append(preview(failedPairs))
                    .append("; first failure: ").append(firstFailure);
        }

        String line = "Exchange rate refresh {} ({}): base={}, saved={} (bulk={}, individual={}), failed={}, retried={}, took={} ms{}";
        if (saved == 0) {
            log.error(line, "failed", trigger, baseCurrency, saved, bulkSaved, individuallyRefreshed,
                    failedPairs.size(), retried, tookMs, problems);
        } else {
            log.warn(line, "finished with problems", trigger, baseCurrency, saved, bulkSaved, individuallyRefreshed,
                    failedPairs.size(), retried, tookMs, problems);
        }
        logSavedRates(trigger, savedRates);
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