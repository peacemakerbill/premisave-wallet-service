package com.premisave.wallet.commercial.controller;

import com.premisave.wallet.commercial.dto.PageViews;
import com.premisave.wallet.commercial.dto.billing.BillingViews;
import com.premisave.wallet.commercial.dto.billing.CreatePlanRequest;
import com.premisave.wallet.commercial.dto.billing.UpdatePlanRequest;
import com.premisave.wallet.commercial.security.CommercialContext;
import com.premisave.wallet.commercial.security.CommercialContextHolder;
import com.premisave.wallet.commercial.security.CommercialRequestFilter;
import com.premisave.wallet.commercial.service.CommercialIdempotencyService;
import com.premisave.wallet.commercial.service.billing.PlanService;
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

/** Sections 8.1 to 8.4: create, list, read and change subscription plans. */
@RestController("commercialPlanController")
@RequestMapping("/internal/commercial/v1/{env:live|sandbox}/plans")
public class CommercialPlanController {

    private final PlanService plans;

    public CommercialPlanController(PlanService plans) {
        this.plans = plans;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestAttribute(CommercialRequestFilter.ATTR_BODY_HASH) String bodyHash,
            @Valid @RequestBody CreatePlanRequest req) {
        CommercialIdempotencyService.requireValidKey(idempotencyKey);
        CommercialContext ctx = CommercialContextHolder.require();
        CommercialIdempotencyService.Outcome<Map<String, Object>> out = plans.create(ctx, req, idempotencyKey, bodyHash);
        return Responses.idempotent("Plan created", out.replayed(), out.value(), HttpStatus.CREATED);
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(
            @RequestParam Map<String, String> filters,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return Responses.page("Plans retrieved",
                plans.list(CommercialContextHolder.require(), filters, PageViews.pageable(page, size, sort)),
                BillingViews::plan);
    }

    @GetMapping("/{planId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(@PathVariable String planId) {
        return Responses.ok("Plan retrieved", plans.get(CommercialContextHolder.require(), planId));
    }

    @PutMapping("/{planId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> update(@PathVariable String planId,
                                                                   @Valid @RequestBody UpdatePlanRequest req) {
        return Responses.ok("Plan updated", plans.update(CommercialContextHolder.require(), planId, req));
    }
}
