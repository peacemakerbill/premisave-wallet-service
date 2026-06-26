package com.premisave.wallet.controller;

import com.premisave.wallet.dto.*;
import com.premisave.wallet.entity.Disbursement;
import com.premisave.wallet.enums.TransactionStatus;
import com.premisave.wallet.enums.TransactionType;
import com.premisave.wallet.service.AdminWalletService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/wallet")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'FINANCE', 'OPERATIONS')")
public class AdminWalletController {

    private final AdminWalletService adminWalletService;

    // ==================== WALLET MANAGEMENT ====================

    @GetMapping("/wallets")
    public ResponseEntity<ApiResponse<Page<WalletResponse>>> getAllWallets(Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success("Wallets retrieved successfully", 
                adminWalletService.getAllWallets(pageable)));
    }

    @GetMapping("/wallets/search")
    public ResponseEntity<ApiResponse<List<WalletResponse>>> searchWallets(@RequestParam String query) {
        return ResponseEntity.ok(ApiResponse.success("Wallets search results", 
                adminWalletService.searchWallets(query)));
    }

    @GetMapping("/wallets/{userId}")
    public ResponseEntity<ApiResponse<WalletResponse>> getWalletByUserId(@PathVariable String userId) {
        return ResponseEntity.ok(ApiResponse.success("Wallet retrieved", 
                adminWalletService.getWalletByUserId(userId)));
    }

    @PutMapping("/wallets/{userId}/freeze")
    public ResponseEntity<ApiResponse<WalletResponse>> freezeWallet(@PathVariable String userId) {
        return ResponseEntity.ok(ApiResponse.success("Wallet frozen successfully", 
                adminWalletService.freezeWallet(userId)));
    }

    @PutMapping("/wallets/{userId}/unfreeze")
    public ResponseEntity<ApiResponse<WalletResponse>> unfreezeWallet(@PathVariable String userId) {
        return ResponseEntity.ok(ApiResponse.success("Wallet unfrozen successfully", 
                adminWalletService.unfreezeWallet(userId)));
    }

    // ==================== MANUAL ADJUSTMENTS ====================

    @PostMapping("/wallets/{userId}/credit")
    public ResponseEntity<ApiResponse<PaymentResponse>> creditWallet(
            @PathVariable String userId,
            @Valid @RequestBody ManualAdjustmentRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Wallet credited successfully", 
                adminWalletService.creditWallet(userId, request)));
    }

    @PostMapping("/wallets/{userId}/debit")
    public ResponseEntity<ApiResponse<PaymentResponse>> debitWallet(
            @PathVariable String userId,
            @Valid @RequestBody ManualAdjustmentRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Wallet debited successfully", 
                adminWalletService.debitWallet(userId, request)));
    }

    // ==================== TRANSACTIONS ====================

    @GetMapping("/transactions")
    public ResponseEntity<ApiResponse<Page<TransactionResponse>>> getAllTransactions(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) TransactionType type,
            @RequestParam(required = false) TransactionStatus status,
            @RequestParam(required = false) LocalDate fromDate,
            @RequestParam(required = false) LocalDate toDate,
            Pageable pageable) {
        
        return ResponseEntity.ok(ApiResponse.success("Transactions retrieved", 
                adminWalletService.getAllTransactions(userId, type, status, fromDate, toDate, pageable)));
    }

    @GetMapping("/transactions/{transactionId}")
    public ResponseEntity<ApiResponse<TransactionResponse>> getTransactionById(@PathVariable String transactionId) {
        return ResponseEntity.ok(ApiResponse.success("Transaction retrieved", 
                adminWalletService.getTransactionById(transactionId)));
    }

    // ==================== DISBURSEMENTS ====================

    @GetMapping("/disbursements/pending")
    public ResponseEntity<ApiResponse<List<Disbursement>>> getPendingDisbursements() {
        return ResponseEntity.ok(ApiResponse.success("Pending disbursements retrieved", 
                adminWalletService.getPendingDisbursements()));
    }

    @PostMapping("/disbursements/{disbursementId}/approve")
    public ResponseEntity<ApiResponse<DisbursementResponse>> approveDisbursement(@PathVariable String disbursementId) {
        return ResponseEntity.ok(ApiResponse.success("Disbursement approved", 
                adminWalletService.approveDisbursement(disbursementId)));
    }

    @PostMapping("/disbursements/{disbursementId}/reject")
    public ResponseEntity<ApiResponse<DisbursementResponse>> rejectDisbursement(
            @PathVariable String disbursementId,
            @RequestParam String reason) {
        return ResponseEntity.ok(ApiResponse.success("Disbursement rejected", 
                adminWalletService.rejectDisbursement(disbursementId, reason)));
    }

    // ==================== REPORTS & ANALYTICS ====================

    @GetMapping("/reports/summary")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSystemSummary() {
        return ResponseEntity.ok(ApiResponse.success("System financial summary", 
                adminWalletService.getSystemSummary()));
    }

    @GetMapping("/reports/daily")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDailyReport(@RequestParam LocalDate date) {
        return ResponseEntity.ok(ApiResponse.success("Daily report retrieved", 
                adminWalletService.getDailyReport(date)));
    }

    @GetMapping("/reports/balance-overview")
    public ResponseEntity<ApiResponse<Map<String, BigDecimal>>> getTotalBalanceOverview() {
        return ResponseEntity.ok(ApiResponse.success("Total balance overview", 
                adminWalletService.getTotalBalanceOverview()));
    }
}