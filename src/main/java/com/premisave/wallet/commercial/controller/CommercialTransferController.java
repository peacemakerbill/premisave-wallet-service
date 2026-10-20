package com.premisave.wallet.commercial.controller;

import com.premisave.wallet.commercial.dto.payment.CreateTransferRequest;
import com.premisave.wallet.commercial.security.CommercialContext;
import com.premisave.wallet.commercial.security.CommercialContextHolder;
import com.premisave.wallet.commercial.security.CommercialRequestFilter;
import com.premisave.wallet.commercial.service.CommercialIdempotencyService;
import com.premisave.wallet.commercial.service.transfer.CommercialTransferService;
import com.premisave.wallet.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Section 10.9: transfers from the merchant balance to a Premisave wallet. Reads go through /transactions. */
@RestController("commercialTransferController")
@RequestMapping("/internal/commercial/v1/{env:live|sandbox}/transfers")
public class CommercialTransferController {

    private final CommercialTransferService transfers;

    public CommercialTransferController(CommercialTransferService transfers) {
        this.transfers = transfers;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestAttribute(CommercialRequestFilter.ATTR_BODY_HASH) String bodyHash,
            @Valid @RequestBody CreateTransferRequest req) {
        CommercialIdempotencyService.requireValidKey(idempotencyKey);
        CommercialContext ctx = CommercialContextHolder.require();
        CommercialIdempotencyService.Outcome<Map<String, Object>> out = transfers.create(ctx, req, idempotencyKey, bodyHash);
        return Responses.idempotent("Transfer created", out.replayed(), out.value(), HttpStatus.CREATED);
    }
}
