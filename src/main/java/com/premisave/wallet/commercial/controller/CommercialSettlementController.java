package com.premisave.wallet.commercial.controller;

import com.premisave.wallet.commercial.dto.PageViews;
import com.premisave.wallet.commercial.dto.Views;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.security.CommercialContext;
import com.premisave.wallet.commercial.security.CommercialContextHolder;
import com.premisave.wallet.commercial.service.BalanceService;
import com.premisave.wallet.commercial.service.TransactionQueryService;
import com.premisave.wallet.dto.ApiResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;

/** Balance, statement and the combined transaction view. Settlements, transfers and reports have their own controllers. */
@RestController
@RequestMapping("/internal/commercial/v1/{env:live|sandbox}")
public class CommercialSettlementController {

    private final BalanceService balances;
    private final TransactionQueryService queries;

    public CommercialSettlementController(BalanceService balances, TransactionQueryService queries) {
        this.balances = balances;
        this.queries = queries;
    }

    @GetMapping("/balance")
    public ResponseEntity<ApiResponse<Map<String, Object>>> balance(@RequestParam(required = false) String currency) {
        return Responses.ok("Balance retrieved", balances.balance(CommercialContextHolder.require(), currency));
    }

    @GetMapping("/statement")
    public ResponseEntity<?> statement(
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(required = false, defaultValue = "JSON") String format,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        CommercialContext ctx = CommercialContextHolder.require();
        Instant start = instant(from, "from");
        Instant end = instant(to, "to");
        String f = format.toUpperCase(Locale.ROOT);
        if (f.equals("CSV")) {
            String csv = balances.statementCsv(ctx, start, end);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"statement.csv\"")
                    .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                    .body(csv);
        }
        if (!f.equals("JSON")) throw CommercialException.validation("format", "must be JSON or CSV");
        int p = page == null ? 0 : page;
        int s = size == null ? 20 : size;
        PageViews.pageable(p, s, null);
        return Responses.ok("Statement retrieved", balances.statement(ctx, start, end, p, s));
    }

    @GetMapping("/transactions")
    public ResponseEntity<ApiResponse<Map<String, Object>>> transactions(
            @RequestParam Map<String, String> filters,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return Responses.page("Transactions retrieved",
                queries.search(CommercialContextHolder.require(), null, filters, PageViews.pageable(page, size, sort)),
                Views::transaction);
    }

    @GetMapping("/transactions/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> transaction(@PathVariable String id) {
        return Responses.ok("Transaction retrieved",
                Views.transaction(queries.get(CommercialContextHolder.require(), id, null)));
    }

    private static Instant instant(String value, String field) {
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw CommercialException.validation(field, "must be an ISO 8601 UTC timestamp such as 2026-10-14T00:00:00Z");
        }
    }
}
