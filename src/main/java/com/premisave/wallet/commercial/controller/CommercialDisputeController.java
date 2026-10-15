package com.premisave.wallet.commercial.controller;

import com.premisave.wallet.commercial.dispute.DisputeService;
import com.premisave.wallet.commercial.dispute.DisputeViews;
import com.premisave.wallet.commercial.dto.PageViews;
import com.premisave.wallet.commercial.dto.SubmitEvidenceRequest;
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

/** Sections 9.4 to 9.6: disputes and their evidence. */
@RestController("commercialDisputeController")
@RequestMapping("/internal/commercial/v1/{env:live|sandbox}/disputes")
public class CommercialDisputeController {

    private final DisputeService disputes;

    public CommercialDisputeController(DisputeService disputes) {
        this.disputes = disputes;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(
            @RequestParam Map<String, String> filters,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return Responses.page("Disputes retrieved",
                disputes.list(CommercialContextHolder.require(), filters, PageViews.pageable(page, size, sort)),
                DisputeViews::dispute);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(@PathVariable String id) {
        return Responses.ok("Dispute retrieved", DisputeViews.dispute(disputes.get(CommercialContextHolder.require(), id)));
    }

    @PostMapping("/{id}/evidence")
    public ResponseEntity<ApiResponse<Map<String, Object>>> evidence(
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestAttribute(CommercialRequestFilter.ATTR_BODY_HASH) String bodyHash,
            @Valid @RequestBody SubmitEvidenceRequest req) {
        CommercialIdempotencyService.requireValidKey(idempotencyKey);
        CommercialContext ctx = CommercialContextHolder.require();
        CommercialIdempotencyService.Outcome<Map<String, Object>> out = disputes.submitEvidence(ctx, id, req, idempotencyKey, bodyHash);
        return Responses.idempotent("Evidence recorded", out.replayed(), out.value(), HttpStatus.OK);
    }
}
