package com.premisave.wallet.commercial.controller;

import com.premisave.wallet.commercial.dto.PageViews;
import com.premisave.wallet.commercial.dto.billing.BillingViews;
import com.premisave.wallet.commercial.dto.billing.CancelSubscriptionRequest;
import com.premisave.wallet.commercial.dto.billing.CreateSubscriptionRequest;
import com.premisave.wallet.commercial.dto.billing.PauseSubscriptionRequest;
import com.premisave.wallet.commercial.dto.billing.ResumeSubscriptionRequest;
import com.premisave.wallet.commercial.dto.billing.RetryInvoiceRequest;
import com.premisave.wallet.commercial.dto.billing.UpdateSubscriptionRequest;
import com.premisave.wallet.commercial.security.CommercialContext;
import com.premisave.wallet.commercial.security.CommercialContextHolder;
import com.premisave.wallet.commercial.security.CommercialRequestFilter;
import com.premisave.wallet.commercial.service.CommercialIdempotencyService;
import com.premisave.wallet.commercial.service.billing.InvoiceService;
import com.premisave.wallet.commercial.service.billing.SubscriptionService;
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

/** Sections 8.9 to 8.17: subscriptions, their invoices, and retrying an invoice. */
@RestController("commercialSubscriptionController")
@RequestMapping("/internal/commercial/v1/{env:live|sandbox}")
public class CommercialSubscriptionController {

    private final SubscriptionService subscriptions;
    private final InvoiceService invoices;

    public CommercialSubscriptionController(SubscriptionService subscriptions, InvoiceService invoices) {
        this.subscriptions = subscriptions;
        this.invoices = invoices;
    }

    @PostMapping("/subscriptions")
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestAttribute(CommercialRequestFilter.ATTR_BODY_HASH) String bodyHash,
            @Valid @RequestBody CreateSubscriptionRequest req) {
        CommercialIdempotencyService.requireValidKey(idempotencyKey);
        CommercialContext ctx = CommercialContextHolder.require();
        CommercialIdempotencyService.Outcome<Map<String, Object>> out = subscriptions.create(ctx, req, idempotencyKey, bodyHash);
        return Responses.idempotent("Subscription created", out.replayed(), out.value(), HttpStatus.CREATED);
    }

    @GetMapping("/subscriptions")
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(
            @RequestParam Map<String, String> filters,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return Responses.page("Subscriptions retrieved",
                subscriptions.list(CommercialContextHolder.require(), filters, PageViews.pageable(page, size, sort)),
                BillingViews::subscription);
    }

    @GetMapping("/subscriptions/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(@PathVariable String id) {
        return Responses.ok("Subscription retrieved", subscriptions.get(CommercialContextHolder.require(), id));
    }

    @PostMapping("/subscriptions/{id}/pause")
    public ResponseEntity<ApiResponse<Map<String, Object>>> pause(@PathVariable String id,
                                                                  @Valid @RequestBody(required = false) PauseSubscriptionRequest req) {
        return Responses.ok("Subscription paused", subscriptions.pause(CommercialContextHolder.require(), id, req));
    }

    @PostMapping("/subscriptions/{id}/resume")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resume(@PathVariable String id,
                                                                   @Valid @RequestBody(required = false) ResumeSubscriptionRequest req) {
        return Responses.ok("Subscription resumed", subscriptions.resume(CommercialContextHolder.require(), id, req));
    }

    @PostMapping("/subscriptions/{id}/cancel")
    public ResponseEntity<ApiResponse<Map<String, Object>>> cancel(@PathVariable String id,
                                                                   @Valid @RequestBody(required = false) CancelSubscriptionRequest req) {
        return Responses.ok("Subscription cancelled", subscriptions.cancel(CommercialContextHolder.require(), id, req));
    }

    @PutMapping("/subscriptions/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> update(@PathVariable String id,
                                                                   @Valid @RequestBody UpdateSubscriptionRequest req) {
        return Responses.ok("Subscription updated", subscriptions.update(CommercialContextHolder.require(), id, req));
    }

    @GetMapping("/subscriptions/{id}/invoices")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listInvoices(
            @PathVariable String id,
            @RequestParam Map<String, String> filters,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return Responses.page("Invoices retrieved",
                invoices.listFor(CommercialContextHolder.require(), id, filters, PageViews.pageable(page, size, sort)),
                BillingViews::invoice);
    }

    @PostMapping("/invoices/{id}/retry")
    public ResponseEntity<ApiResponse<Map<String, Object>>> retry(
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestAttribute(CommercialRequestFilter.ATTR_BODY_HASH) String bodyHash,
            @Valid @RequestBody(required = false) RetryInvoiceRequest req) {
        CommercialIdempotencyService.requireValidKey(idempotencyKey);
        CommercialContext ctx = CommercialContextHolder.require();
        CommercialIdempotencyService.Outcome<Map<String, Object>> out = invoices.retry(ctx, id, req, idempotencyKey, bodyHash);
        return Responses.idempotent("Invoice retried", out.replayed(), out.value(), HttpStatus.OK);
    }
}
