package com.premisave.wallet.controller;

import com.premisave.wallet.dto.ApiResponse;
import com.premisave.wallet.dto.ExchangeRateEntry;
import com.premisave.wallet.entity.ExchangeRate;
import com.premisave.wallet.service.ExchangeRateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Manual add/update and read access to the persisted exchange rates
 * ExchangeRateService caches in Redis and refreshes automatically on a
 * schedule (see that class's own javadoc). Same
 * ADMIN/FINANCE/OPERATIONS security posture as every other admin
 * controller in this codebase.
 */
@RestController
@RequestMapping("/admin/exchange-rates")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'FINANCE', 'OPERATIONS')")
public class AdminExchangeRateController {

    private final ExchangeRateService exchangeRateService;

    /**
     * Adds new pairs and updates existing ones in the same call — an
     * array, so multiple rates can be set at once. An entry whose
     * base/quote pair already exists is updated in place; a new pair is
     * inserted. Immediately refreshes Redis too, so a manually-set rate
     * is available on the very next read.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<String>> saveOrUpdateRates(@RequestBody List<ExchangeRateEntry> rates) {
        exchangeRateService.saveOrUpdateRates(rates);
        return ResponseEntity.ok(ApiResponse.success("Exchange rates saved", rates.size() + " rate(s) saved/updated"));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ExchangeRate>>> getAllRates() {
        return ResponseEntity.ok(ApiResponse.success("Exchange rates retrieved", exchangeRateService.getAllRates()));
    }
}