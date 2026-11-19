package com.premisave.wallet.controller;

import com.premisave.wallet.dto.ApiResponse;
import com.premisave.wallet.dto.ActivityResponse;
import com.premisave.wallet.service.TransactionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;

    @GetMapping("/history")
    public ResponseEntity<ApiResponse<List<ActivityResponse>>> getHistory(Authentication auth) {
        String userId = auth.getName();
        List<ActivityResponse> history = transactionService.getTransactionHistory(userId);
        return ResponseEntity.ok(ApiResponse.success("Transaction history retrieved", history));
    }
}