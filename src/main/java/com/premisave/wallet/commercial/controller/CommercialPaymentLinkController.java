package com.premisave.wallet.commercial.controller;

import com.premisave.wallet.commercial.dto.PageViews;
import com.premisave.wallet.commercial.dto.link.CreatePaymentLinkRequest;
import com.premisave.wallet.commercial.dto.link.UpdatePaymentLinkRequest;
import com.premisave.wallet.commercial.security.CommercialContext;
import com.premisave.wallet.commercial.security.CommercialContextHolder;
import com.premisave.wallet.commercial.security.CommercialRequestFilter;
import com.premisave.wallet.commercial.service.CommercialIdempotencyService;
import com.premisave.wallet.commercial.service.link.PaymentLinkService;
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

/** Sections 5.5 to 5.8: create, list, read and change payment links. */
@RestController("commercialPaymentLinkController")
@RequestMapping("/internal/commercial/v1/{env:live|sandbox}/payment-links")
public class CommercialPaymentLinkController {

    private final PaymentLinkService links;

    public CommercialPaymentLinkController(PaymentLinkService links) {
        this.links = links;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestAttribute(CommercialRequestFilter.ATTR_BODY_HASH) String bodyHash,
            @Valid @RequestBody CreatePaymentLinkRequest req) {
        CommercialIdempotencyService.requireValidKey(idempotencyKey);
        CommercialContext ctx = CommercialContextHolder.require();
        CommercialIdempotencyService.Outcome<Map<String, Object>> out = links.create(ctx, req, idempotencyKey, bodyHash);
        return Responses.idempotent("Payment link created", out.replayed(), out.value(), HttpStatus.CREATED);
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(
            @RequestParam Map<String, String> filters,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return Responses.page("Payment links retrieved",
                links.list(CommercialContextHolder.require(), filters, PageViews.pageable(page, size, sort)),
                links::view);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(@PathVariable String id) {
        return Responses.ok("Payment link retrieved", links.detail(CommercialContextHolder.require(), id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> update(@PathVariable String id,
                                                                   @Valid @RequestBody UpdatePaymentLinkRequest req) {
        return Responses.ok("Payment link updated", links.update(CommercialContextHolder.require(), id, req));
    }
}
