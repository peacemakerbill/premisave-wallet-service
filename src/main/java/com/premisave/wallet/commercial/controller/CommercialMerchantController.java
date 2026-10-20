package com.premisave.wallet.commercial.controller;

import com.premisave.wallet.commercial.dto.merchant.ChangeStatusRequest;
import com.premisave.wallet.commercial.dto.merchant.CreateMerchantRequest;
import com.premisave.wallet.commercial.dto.merchant.UpdateConfigRequest;
import com.premisave.wallet.commercial.service.MerchantService;
import com.premisave.wallet.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Merchant provisioning, called by the developer service. Not environment scoped, so no merchant or app headers apply. */
@RestController
@RequestMapping("/internal/commercial/v1/merchants")
public class CommercialMerchantController {

    private final MerchantService merchants;

    public CommercialMerchantController(MerchantService merchants) {
        this.merchants = merchants;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(@Valid @RequestBody CreateMerchantRequest req) {
        return Responses.created("Merchant created", merchants.create(req));
    }

    /** Every business of one developer account, with the state of each onboarding application. */
    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(@RequestParam String developerAccountId) {
        return Responses.ok("Merchants retrieved", merchants.listForDeveloper(developerAccountId));
    }

    @GetMapping("/by-number/{merchantNumber}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> byNumber(@PathVariable String merchantNumber) {
        return Responses.ok("Merchant retrieved", merchants.getByNumber(merchantNumber));
    }

    @GetMapping("/{merchantId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(@PathVariable String merchantId) {
        return Responses.ok("Merchant retrieved", merchants.get(merchantId));
    }

    @PutMapping("/{merchantId}/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status(@PathVariable String merchantId,
                                                                   @Valid @RequestBody ChangeStatusRequest req) {
        return Responses.ok("Merchant status updated", merchants.changeStatus(merchantId, req));
    }

    @PutMapping("/{merchantId}/config")
    public ResponseEntity<ApiResponse<Map<String, Object>>> config(@PathVariable String merchantId,
                                                                   @Valid @RequestBody UpdateConfigRequest req) {
        return Responses.ok("Merchant configuration updated", merchants.updateConfig(merchantId, req));
    }
}
