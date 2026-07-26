package com.premisave.wallet.controller;

import com.premisave.wallet.dto.ApiResponse;
import com.premisave.wallet.dto.B2PochiRequest;
import com.premisave.wallet.dto.DisbursementRequest;
import com.premisave.wallet.dto.DisbursementResponse;
import com.premisave.wallet.service.DisbursementService;
import jakarta.servlet.http.HttpServletRequest;
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
    public ResponseEntity<ApiResponse<DisbursementResponse>> disburse(
            @Valid @RequestBody DisbursementRequest request,
            Authentication auth,
            HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute("userId");
        if (userId == null) userId = auth.getName();
        DisbursementResponse response = disbursementService.processDisbursement(userId, request);
        return ResponseEntity.ok(ApiResponse.success("Disbursement initiated", response));
    }

    /**
     * Pays directly into the caller's own Pochi la Biashara business wallet
     * (CommandID BusinessPayToPochi) instead of their main M-Pesa balance.
     * Unlike the generic /disbursements above, this does NOT deduct from
     * the caller's own Premisave wallet — it's a disbursement from Premisave's
     * B2C shortcode float, tracked for audit/reconciliation via the
     * Disbursement record (channel=B2C_POCHI), same as B2B/B2C Top Up. The
     * recipient phone number is always resolved from the caller's own
     * verified profile, same as the B2C endpoint above.
     * POST /disbursements/b2pochi
     */
    @PostMapping("/b2pochi")
    public ResponseEntity<ApiResponse<DisbursementResponse>> disburseToPochi(
            @Valid @RequestBody B2PochiRequest request,
            Authentication auth,
            HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute("userId");
        if (userId == null) userId = auth.getName();
        DisbursementResponse response = disbursementService.processB2PochiPayment(userId, request);
        return ResponseEntity.ok(ApiResponse.success("B2Pochi disbursement initiated", response));
    }
}