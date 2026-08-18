package com.premisave.wallet.controller;

import com.premisave.wallet.dto.ApiResponse;
import com.premisave.wallet.dto.DepositRecordResponse;
import com.premisave.wallet.service.DepositService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only deposit history — first standalone controller for Deposit,
 * mirroring how DisbursementController/PaymentController already exist as
 * their own dedicated controllers rather than being folded into
 * WalletController.
 */
@RestController
@RequestMapping("/deposits")
@RequiredArgsConstructor
public class DepositController {

    private final DepositService depositService;

    /**
     * Every deposit for the authenticated user, across all five providers,
     * newest first.
     * GET /deposits/history
     */
    @GetMapping("/history")
    public ResponseEntity<ApiResponse<List<DepositRecordResponse>>> getHistory(
            Authentication auth, HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute("userId");
        if (userId == null) userId = auth.getName();
        List<DepositRecordResponse> history = depositService.getDepositHistory(userId);
        return ResponseEntity.ok(ApiResponse.success("Deposit history retrieved", history));
    }
}