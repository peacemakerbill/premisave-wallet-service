package com.premisave.wallet.commercial.controller;

import com.premisave.wallet.commercial.dto.CreateAccountReferenceRequest;
import com.premisave.wallet.commercial.link.AccountReferenceService;
import com.premisave.wallet.commercial.security.CommercialContext;
import com.premisave.wallet.commercial.security.CommercialContextHolder;
import com.premisave.wallet.commercial.security.CommercialRequestFilter;
import com.premisave.wallet.commercial.service.CommercialIdempotencyService;
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

/** Section 5.9: issue an account reference for paying a shared paybill. */
@RestController("commercialAccountReferenceController")
@RequestMapping("/internal/commercial/v1/{env:live|sandbox}/payments/account-references")
public class CommercialAccountReferenceController {

    private final AccountReferenceService references;

    public CommercialAccountReferenceController(AccountReferenceService references) {
        this.references = references;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestAttribute(CommercialRequestFilter.ATTR_BODY_HASH) String bodyHash,
            @Valid @RequestBody CreateAccountReferenceRequest req) {
        CommercialIdempotencyService.requireValidKey(idempotencyKey);
        CommercialContext ctx = CommercialContextHolder.require();
        CommercialIdempotencyService.Outcome<Map<String, Object>> out = references.create(ctx, req, idempotencyKey, bodyHash);
        return Responses.idempotent("Account reference created", out.replayed(), out.value(), HttpStatus.CREATED);
    }
}
