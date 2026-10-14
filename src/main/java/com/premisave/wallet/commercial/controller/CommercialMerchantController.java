package com.premisave.wallet.commercial.controller;

import com.premisave.wallet.commercial.dto.ChangeStatusRequest;
import com.premisave.wallet.commercial.dto.CreateAppRequest;
import com.premisave.wallet.commercial.dto.CreateMerchantRequest;
import com.premisave.wallet.commercial.dto.PageViews;
import com.premisave.wallet.commercial.dto.UpdateAppRequest;
import com.premisave.wallet.commercial.dto.UpdateConfigRequest;
import com.premisave.wallet.commercial.dto.Views;
import com.premisave.wallet.commercial.enums.AppStatus;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.service.MerchantService;
import com.premisave.wallet.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.Map;

/** Provisioning, called by the developer service. Not environment scoped, so no merchant or app headers apply. */
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

    @PostMapping("/{merchantId}/apps")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createApp(@PathVariable String merchantId,
                                                                      @Valid @RequestBody CreateAppRequest req) {
        return Responses.created("App created", merchants.createApp(merchantId, req));
    }

    @GetMapping("/{merchantId}/apps")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listApps(
            @PathVariable String merchantId,
            @RequestParam(required = false) String environment,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        AppStatus appStatus = null;
        if (status != null && !status.isBlank()) {
            try {
                appStatus = AppStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw CommercialException.validation("status", "must be ACTIVE or SUSPENDED");
            }
        }
        return Responses.page("Apps retrieved",
                merchants.listApps(merchantId, environment, appStatus, PageViews.pageable(page, size, null)), Views::app);
    }

    @PutMapping("/{merchantId}/apps/{appId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateApp(@PathVariable String merchantId,
                                                                      @PathVariable String appId,
                                                                      @Valid @RequestBody UpdateAppRequest req) {
        return Responses.ok("App updated", merchants.updateApp(merchantId, appId, req));
    }
}
