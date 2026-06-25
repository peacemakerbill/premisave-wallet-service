package com.premisave.wallet.controller;

import com.premisave.wallet.dto.ApiResponse;
import com.premisave.wallet.dto.DepositRequest;
import com.premisave.wallet.dto.PaymentResponse;
import com.premisave.wallet.dto.TransferRequest;
import com.premisave.wallet.dto.WalletBalanceResponse;
import com.premisave.wallet.dto.WalletResponse;
import com.premisave.wallet.service.DepositService;
import com.premisave.wallet.service.TransferService;
import com.premisave.wallet.service.WalletService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/wallet")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;
    private final TransferService transferService;
    private final DepositService depositService;

    @GetMapping
    public ResponseEntity<ApiResponse<WalletResponse>> getMyWallet(Authentication auth) {
        String email = auth.getName();
        return ResponseEntity.ok(ApiResponse.success("Wallet retrieved", walletService.getWallet(email)));
    }

    @GetMapping("/balance")
    public ResponseEntity<ApiResponse<WalletBalanceResponse>> getBalance(Authentication auth) {
        String email = auth.getName();
        return ResponseEntity.ok(ApiResponse.success("Balance retrieved", walletService.getBalance(email)));
    }

    /**
     * Creates wallet using userId from JWT claim (no placeholder needed).
     */
    @PostMapping("/create")
    public ResponseEntity<ApiResponse<WalletResponse>> createWallet(
            Authentication auth,
            HttpServletRequest request) {
        String email  = auth.getName();
        String userId = (String) request.getAttribute("userId"); // set by JwtAuthenticationFilter
        if (userId == null) userId = email; // safe fallback if claim absent
        WalletResponse wallet = walletService.createWallet(userId, email);
        return ResponseEntity.ok(ApiResponse.success("Wallet created", wallet));
    }

    @PostMapping("/deposit")
    public ResponseEntity<ApiResponse<PaymentResponse>> deposit(
            @Valid @RequestBody DepositRequest depositRequest,
            Authentication auth,
            HttpServletRequest request) {
        String email  = auth.getName();
        String userId = (String) request.getAttribute("userId");
        if (userId == null) userId = email;
        PaymentResponse response = depositService.initiateDeposit(userId, email, depositRequest);
        return ResponseEntity.ok(ApiResponse.success("Deposit initiated", response));
    }

    @PostMapping("/transfer")
    public ResponseEntity<ApiResponse<PaymentResponse>> transfer(
            @Valid @RequestBody TransferRequest transferRequest,
            Authentication auth,
            HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        String email  = auth.getName();
        if (userId == null) userId = email;
        PaymentResponse response = transferService.transfer(userId, transferRequest);
        return ResponseEntity.ok(ApiResponse.success("Transfer successful", response));
    }

    @PutMapping("/freeze")
    public ResponseEntity<ApiResponse<WalletResponse>> freeze(
            Authentication auth,
            HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        if (userId == null) userId = auth.getName();
        return ResponseEntity.ok(ApiResponse.success("Wallet frozen", walletService.freezeWallet(userId)));
    }

    @PutMapping("/unfreeze")
    public ResponseEntity<ApiResponse<WalletResponse>> unfreeze(
            Authentication auth,
            HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        if (userId == null) userId = auth.getName();
        return ResponseEntity.ok(ApiResponse.success("Wallet unfrozen", walletService.unfreezeWallet(userId)));
    }
}