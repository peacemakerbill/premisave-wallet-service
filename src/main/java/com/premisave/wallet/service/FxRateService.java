package com.premisave.wallet.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * Live foreign-exchange rates via Frankfurter (https://frankfurter.dev),
 * used to convert PayPal's USD-only amounts to/from the wallet's KES
 * balance (see DepositService and DisbursementService).
 *
 * IMPORTANT: this deliberately uses Frankfurter's v2 API
 * (https://api.frankfurter.dev/v2/...), NOT v1. The legacy v1 API only
 * serves the 31 currencies published directly by the ECB, which does NOT
 * include KES — a call to v1 for USD->KES will 404. v2 aggregates 84 central
 * banks and does cover KES. See https://frankfurter.dev/currencies/kes/
 *
 * No caching: every call hits the live API, so a rate is never more than
 * one HTTP round-trip stale. No fallback rate either — if Frankfurter is
 * unreachable, this throws and the caller must fail/refund the transaction
 * rather than silently substitute a stale or guessed figure.
 */
@Slf4j
@Service
public class FxRateService {

    private static final String BASE_URL = "https://api.frankfurter.dev/v2/rate";

    private final OkHttpClient http = new OkHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Returns how many units of {@code quote} equal 1 unit of {@code base}
     * right now, e.g. getRate("USD", "KES") returns the current USD->KES rate.
     *
     * @throws IllegalStateException if Frankfurter is unreachable, returns
     *         a non-2xx response, or the response can't be parsed — callers
     *         must treat this as a hard failure, not fall back to any
     *         cached or static rate.
     */
    public BigDecimal getRate(String base, String quote) {
        HttpUrl url = HttpUrl.parse(BASE_URL + "/" + base.toUpperCase() + "/" + quote.toUpperCase());
        if (url == null) {
            throw new IllegalStateException("Failed to build Frankfurter URL for " + base + "->" + quote);
        }

        Request request = new Request.Builder().url(url).get().build();

        try (Response response = http.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                throw new IllegalStateException(
                        "Frankfurter rate lookup failed (" + response.code() + ") for " + base + "->" + quote + ": " + body);
            }

            JsonNode node = objectMapper.readTree(body);
            JsonNode rateNode = node.path("rate");
            if (rateNode.isMissingNode() || !rateNode.isNumber()) {
                throw new IllegalStateException(
                        "Frankfurter response missing numeric 'rate' for " + base + "->" + quote + ": " + body);
            }

            BigDecimal rate = rateNode.decimalValue();
            log.info("Frankfurter live rate fetched: {} -> {} = {}", base, quote, rate);
            return rate;
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to reach Frankfurter FX service for " + base + "->" + quote, e);
        }
    }
}