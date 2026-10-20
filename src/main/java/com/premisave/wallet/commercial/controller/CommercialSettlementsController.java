package com.premisave.wallet.commercial.controller;

import com.premisave.wallet.commercial.dto.PageViews;
import com.premisave.wallet.commercial.dto.settlement.CreateSettlementRequest;
import com.premisave.wallet.commercial.dto.settlement.SettlementScheduleRequest;
import com.premisave.wallet.commercial.dto.settlement.SettlementViews;
import com.premisave.wallet.commercial.enums.TxType;
import com.premisave.wallet.commercial.security.CommercialContext;
import com.premisave.wallet.commercial.security.CommercialContextHolder;
import com.premisave.wallet.commercial.security.CommercialRequestFilter;
import com.premisave.wallet.commercial.service.CommercialIdempotencyService;
import com.premisave.wallet.commercial.service.TransactionQueryService;
import com.premisave.wallet.commercial.service.settlement.SettlementService;
import com.premisave.wallet.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Sections 10.5 to 10.8: create, list and read settlements, and save the automatic schedule. */
@RestController("commercialSettlementsController")
@RequestMapping("/internal/commercial/v1/{env:live|sandbox}/settlements")
public class CommercialSettlementsController {

    private final SettlementService settlements;
    private final TransactionQueryService queries;

    public CommercialSettlementsController(SettlementService settlements, TransactionQueryService queries) {
        this.settlements = settlements;
        this.queries = queries;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestAttribute(CommercialRequestFilter.ATTR_BODY_HASH) String bodyHash,
            @Valid @RequestBody CreateSettlementRequest req) {
        CommercialIdempotencyService.requireValidKey(idempotencyKey);
        CommercialContext ctx = CommercialContextHolder.require();
        CommercialIdempotencyService.Outcome<Map<String, Object>> out = settlements.create(ctx, req, idempotencyKey, bodyHash);
        return Responses.idempotent("Settlement created", out.replayed(), out.value(), HttpStatus.CREATED);
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(
            @RequestParam Map<String, String> filters,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return Responses.page("Settlements retrieved",
                queries.search(CommercialContextHolder.require(), TxType.SETTLEMENT, filters, PageViews.pageable(page, size, sort)),
                SettlementViews::settlement);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(@PathVariable String id) {
        return Responses.ok("Settlement retrieved",
                SettlementViews.settlement(queries.get(CommercialContextHolder.require(), id, TxType.SETTLEMENT)));
    }

    @PutMapping("/schedule")
    public ResponseEntity<ApiResponse<Map<String, Object>>> schedule(@Valid @RequestBody SettlementScheduleRequest req) {
        return Responses.ok("Settlement schedule saved", settlements.saveSchedule(CommercialContextHolder.require(), req));
    }
}
