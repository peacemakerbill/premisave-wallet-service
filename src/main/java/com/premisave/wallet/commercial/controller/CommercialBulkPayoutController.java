package com.premisave.wallet.commercial.controller;

import com.premisave.wallet.commercial.batch.BulkPayoutService;
import com.premisave.wallet.commercial.dto.BulkPayoutRequest;
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

/** Sections 6.2 and 6.5: bulk payouts and reading a batch. */
@RestController("commercialBulkPayoutController")
@RequestMapping("/internal/commercial/v1/{env:live|sandbox}/payouts")
public class CommercialBulkPayoutController {

    private final BulkPayoutService bulk;

    public CommercialBulkPayoutController(BulkPayoutService bulk) {
        this.bulk = bulk;
    }

    @PostMapping("/bulk")
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestAttribute(CommercialRequestFilter.ATTR_BODY_HASH) String bodyHash,
            @Valid @RequestBody BulkPayoutRequest req) {
        CommercialIdempotencyService.requireValidKey(idempotencyKey);
        CommercialContext ctx = CommercialContextHolder.require();
        CommercialIdempotencyService.Outcome<Map<String, Object>> out = bulk.create(ctx, req, idempotencyKey, bodyHash);
        return Responses.idempotent("Batch accepted", out.replayed(), out.value(), HttpStatus.ACCEPTED);
    }

    @GetMapping("/batches/{batchId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(
            @PathVariable String batchId,
            @RequestParam(required = false, defaultValue = "false") boolean includeItems,
            @RequestParam(required = false) String itemStatus,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return Responses.ok("Batch retrieved",
                bulk.get(CommercialContextHolder.require(), batchId, includeItems, itemStatus, page, size));
    }
}
