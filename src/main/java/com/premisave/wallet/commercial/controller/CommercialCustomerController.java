package com.premisave.wallet.commercial.controller;

import com.premisave.wallet.commercial.billing.CustomerService;
import com.premisave.wallet.commercial.billing.PaymentMethodService;
import com.premisave.wallet.commercial.dto.AddPaymentMethodRequest;
import com.premisave.wallet.commercial.dto.CreateCustomerRequest;
import com.premisave.wallet.commercial.dto.PageViews;
import com.premisave.wallet.commercial.security.CommercialContext;
import com.premisave.wallet.commercial.security.CommercialContextHolder;
import com.premisave.wallet.commercial.security.CommercialRequestFilter;
import com.premisave.wallet.commercial.service.CommercialIdempotencyService;
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

/** Sections 8.5 to 8.8: customers and their saved payment methods. */
@RestController("commercialCustomerController")
@RequestMapping("/internal/commercial/v1/{env:live|sandbox}/customers")
public class CommercialCustomerController {

    private final CustomerService customers;
    private final PaymentMethodService paymentMethods;

    public CommercialCustomerController(CustomerService customers, PaymentMethodService paymentMethods) {
        this.customers = customers;
        this.paymentMethods = paymentMethods;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(@Valid @RequestBody CreateCustomerRequest req) {
        return Responses.created("Customer created", customers.create(CommercialContextHolder.require(), req));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(
            @RequestParam Map<String, String> filters,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return Responses.page("Customers retrieved",
                customers.list(CommercialContextHolder.require(), filters, PageViews.pageable(page, size, sort)),
                customers::view);
    }

    @GetMapping("/{customerId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(@PathVariable String customerId) {
        return Responses.ok("Customer retrieved", customers.get(CommercialContextHolder.require(), customerId));
    }

    @PostMapping("/{customerId}/payment-methods")
    public ResponseEntity<ApiResponse<Map<String, Object>>> addPaymentMethod(
            @PathVariable String customerId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestAttribute(CommercialRequestFilter.ATTR_BODY_HASH) String bodyHash,
            @Valid @RequestBody AddPaymentMethodRequest req) {
        CommercialIdempotencyService.requireValidKey(idempotencyKey);
        CommercialContext ctx = CommercialContextHolder.require();
        CommercialIdempotencyService.Outcome<Map<String, Object>> out =
                paymentMethods.add(ctx, customerId, req, idempotencyKey, bodyHash);
        return Responses.idempotent("Payment method saved", out.replayed(), out.value(), HttpStatus.CREATED);
    }
}
