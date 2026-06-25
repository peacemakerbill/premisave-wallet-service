package com.premisave.wallet.controller;

import com.premisave.wallet.dto.ApiResponse;
import com.premisave.wallet.dto.DisbursementRequest;
import com.premisave.wallet.dto.DisbursementResponse;
import com.premisave.wallet.service.DisbursementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/disbursements")
@RequiredArgsConstructor
public class DisbursementController {

    private final DisbursementService disbursementService;

    @PostMapping
    public ResponseEntity<ApiResponse<DisbursementResponse>> disburse(@Valid @RequestBody DisbursementRequest request,
                                                                      Authentication auth) {
        String userId = auth.getName();
        DisbursementResponse response = disbursementService.processDisbursement(userId, request);
        return ResponseEntity.ok(ApiResponse.success("Disbursement initiated", response));
    }
}