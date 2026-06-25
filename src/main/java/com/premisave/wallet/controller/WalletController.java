package com.premisave.wallet.controller;

import com.premisave.wallet.dto.ApiResponse;
import com.premisave.wallet.dto.WalletResponse;
import com.premisave.wallet.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/wallet")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;

    @GetMapping
    public ResponseEntity<ApiResponse<WalletResponse>> getMyWallet(Authentication auth) {
        String email = auth.getName();
        WalletResponse wallet = walletService.getWallet(email);
        return ResponseEntity.ok(ApiResponse.success("Wallet retrieved", wallet));
    }

    @PostMapping("/create")
    public ResponseEntity<ApiResponse<WalletResponse>> createWallet(Authentication auth) {
        String email = auth.getName();
        String userId = "user-" + email; // In real case, get from Auth Service
        WalletResponse wallet = walletService.createWallet(userId, email);
        return ResponseEntity.ok(ApiResponse.success("Wallet created", wallet));
    }
}