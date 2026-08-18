package com.premisave.wallet.controller;

import com.premisave.wallet.dto.ApiResponse;
import com.premisave.wallet.dto.PaymentInitiateRequest;
import com.premisave.wallet.dto.PaymentRecordResponse;
import com.premisave.wallet.dto.PaymentResponse;
import com.premisave.wallet.service.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/deduct")
    public ResponseEntity<ApiResponse<PaymentResponse>> deduct(
            @Valid @RequestBody PaymentInitiateRequest request,
            Authentication auth,
            HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute("userId");
        if (userId == null) userId = auth.getName();
        PaymentResponse response = paymentService.deductFromWallet(userId, request);
        return ResponseEntity.ok(ApiResponse.success("Payment processed", response));
    }

    /**
     * Every payment for the authenticated user, newest first.
     * GET /payments/history
     */
    @GetMapping("/history")
    public ResponseEntity<ApiResponse<List<PaymentRecordResponse>>> getHistory(
            Authentication auth, HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute("userId");
        if (userId == null) userId = auth.getName();
        List<PaymentRecordResponse> history = paymentService.getPaymentHistory(userId);
        return ResponseEntity.ok(ApiResponse.success("Payment history retrieved", history));
    }
}