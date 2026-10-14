package com.premisave.wallet.commercial.controller;

import com.premisave.wallet.commercial.dto.CreatePayoutRequest;
import com.premisave.wallet.commercial.dto.PageViews;
import com.premisave.wallet.commercial.dto.Views;
import com.premisave.wallet.commercial.enums.TxType;
import com.premisave.wallet.commercial.security.CommercialContext;
import com.premisave.wallet.commercial.security.CommercialContextHolder;
import com.premisave.wallet.commercial.security.CommercialRequestFilter;
import com.premisave.wallet.commercial.service.CommercialPayoutService;
import com.premisave.wallet.commercial.service.CommercialIdempotencyService;
import com.premisave.wallet.commercial.service.TransactionQueryService;
import com.premisave.wallet.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/internal/commercial/v1/{env:live|sandbox}/payouts")
public class CommercialPayoutController {

    private final CommercialPayoutService payouts;
    private final TransactionQueryService queries;

    public CommercialPayoutController(CommercialPayoutService payouts, TransactionQueryService queries) {
        this.payouts = payouts;
        this.queries = queries;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestAttribute(CommercialRequestFilter.ATTR_BODY_HASH) String bodyHash,
            @Valid @RequestBody CreatePayoutRequest req) {
        CommercialIdempotencyService.requireValidKey(idempotencyKey);
        CommercialContext ctx = CommercialContextHolder.require();
        CommercialIdempotencyService.Outcome<Map<String, Object>> out = payouts.create(ctx, req, idempotencyKey, bodyHash);
        return Responses.idempotent("Payout created", out.replayed(), out.value(), HttpStatus.CREATED);
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(
            @RequestParam Map<String, String> filters,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return Responses.page("Payouts retrieved",
                queries.search(CommercialContextHolder.require(), TxType.PAYOUT, filters, PageViews.pageable(page, size, sort)),
                Views::payout);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(@PathVariable String id) {
        return Responses.ok("Payout retrieved",
                Views.payout(queries.get(CommercialContextHolder.require(), id, TxType.PAYOUT)));
    }
}
