package com.premisave.wallet.commercial.controller;

import com.premisave.wallet.commercial.dto.CreatePaymentRequest;
import com.premisave.wallet.commercial.dto.PageViews;
import com.premisave.wallet.commercial.dto.Views;
import com.premisave.wallet.commercial.enums.TxType;
import com.premisave.wallet.commercial.security.CommercialContext;
import com.premisave.wallet.commercial.security.CommercialContextHolder;
import com.premisave.wallet.commercial.security.CommercialRequestFilter;
import com.premisave.wallet.commercial.service.CommercialPaymentService;
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
@RequestMapping("/internal/commercial/v1/{env:live|sandbox}/payments")
public class CommercialPaymentController {

    private final CommercialPaymentService payments;
    private final TransactionQueryService queries;

    public CommercialPaymentController(CommercialPaymentService payments, TransactionQueryService queries) {
        this.payments = payments;
        this.queries = queries;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestAttribute(CommercialRequestFilter.ATTR_BODY_HASH) String bodyHash,
            @Valid @RequestBody CreatePaymentRequest req) {
        CommercialIdempotencyService.requireValidKey(idempotencyKey);
        CommercialContext ctx = CommercialContextHolder.require();
        CommercialIdempotencyService.Outcome<Map<String, Object>> out = payments.create(ctx, req, idempotencyKey, bodyHash);
        return Responses.idempotent("Payment created", out.replayed(), out.value(), HttpStatus.CREATED);
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(
            @RequestParam Map<String, String> filters,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return Responses.page("Payments retrieved",
                queries.search(CommercialContextHolder.require(), TxType.PAYMENT, filters, PageViews.pageable(page, size, sort)),
                Views::payment);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(@PathVariable String id) {
        return Responses.ok("Payment retrieved",
                Views.payment(queries.get(CommercialContextHolder.require(), id, TxType.PAYMENT)));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<ApiResponse<Map<String, Object>>> cancel(@PathVariable String id) {
        return Responses.ok("Payment cancelled", payments.cancel(CommercialContextHolder.require(), id));
    }
}
