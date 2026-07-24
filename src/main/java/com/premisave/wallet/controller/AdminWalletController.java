package com.premisave.wallet.controller;

import com.premisave.wallet.dto.*;
import com.premisave.wallet.entity.Disbursement;
import com.premisave.wallet.entity.MpesaOperation;
import com.premisave.wallet.enums.TransactionStatus;
import com.premisave.wallet.enums.TransactionType;
import com.premisave.wallet.service.AdminWalletService;
import com.premisave.wallet.service.DisbursementService;
import com.premisave.wallet.service.MpesaOperationsService;
import com.premisave.wallet.service.PullTransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
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
    private final DisbursementService disbursementService;
    private final MpesaOperationsService mpesaOperationsService;
    private final PullTransactionService pullTransactionService;

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

    // ==================== B2B (BUSINESS TO BUSINESS) ====================

    /**
     * Triggers an M-Pesa B2B payment from Premisave's shortcode to another
     * paybill/till (e.g. settling with a vendor or partner business, or
     * BusinessBuyGoods to pay a till/store number — set via request.commandId).
     * Restricted to ADMIN/FINANCE/OPERATIONS via the class-level @PreAuthorize.
     * B2B is a permissioned Safaricom API — must be enabled for the shortcode.
     */
    @PostMapping("/b2b/pay")
    public ResponseEntity<ApiResponse<DisbursementResponse>> payB2B(
            @Valid @RequestBody MpesaB2BRequest request,
            Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success("B2B payment initiated",
                adminWalletService.processB2BPayment(auth.getName(), request)));
    }

    // ==================== B2C ACCOUNT TOP UP ====================

    /**
     * Tops up a B2C shortcode's utility account from Premisave's working
     * account (CommandID BusinessPayToBulk) — internal float management so
     * disbursements don't run dry. Restricted to ADMIN/FINANCE/OPERATIONS
     * via the class-level @PreAuthorize.
     * See https://developer.safaricom.co.ke/apis/B2CAccountTopUp
     */
    @PostMapping("/b2c/top-up")
    public ResponseEntity<ApiResponse<DisbursementResponse>> topUpB2CAccount(
            @Valid @RequestBody B2CTopUpRequest request,
            Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success("B2C top-up initiated",
                adminWalletService.processB2CTopUp(auth.getName(), request)));
    }

    // ==================== B2POCHI (BUSINESS TO POCHI LA BIASHARA) ====================

    /**
     * Disburses from our B2C shortcode straight into a customer's Pochi la
     * Biashara business wallet. Restricted to ADMIN/FINANCE/OPERATIONS via
     * the class-level @PreAuthorize.
     * See https://developer.safaricom.co.ke/apis/BusinessToPochi
     */
    @PostMapping("/mpesa/b2pochi/pay")
    public ResponseEntity<ApiResponse<DisbursementResponse>> payB2Pochi(
            @Valid @RequestBody B2PochiRequest request,
            Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success("B2Pochi payment initiated",
                disbursementService.processB2PochiPayment(auth.getName(), request)));
    }

    // ==================== M-PESA ACCOUNT BALANCE ====================

    /**
     * Triggers a real-time Account Balance query against our own shortcode's
     * Working/Utility/Charges Paid accounts. The actual balances arrive
     * asynchronously via ResultURL — poll GET /mpesa/operations/{conversationId}
     * once the callback has had time to land.
     * See https://developer.safaricom.co.ke/apis/AccountBalance
     */
    @PostMapping("/mpesa/balance/query")
    public ResponseEntity<ApiResponse<MpesaAsyncResponse>> queryAccountBalance(Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success("Account balance query submitted",
                mpesaOperationsService.queryAccountBalance(auth.getName())));
    }

    // ==================== M-PESA TRANSACTION STATUS ====================

    /**
     * Secondary reconciliation mechanism for a C2B/B2B/B2C/Reversal
     * transaction whose ResultURL callback never arrived. Requires either
     * transactionId (M-Pesa receipt) or originatorConversationId in the body.
     * See https://developer.safaricom.co.ke/apis/TransactionStatus
     */
    @PostMapping("/mpesa/transaction-status/query")
    public ResponseEntity<ApiResponse<MpesaAsyncResponse>> queryTransactionStatus(
            @RequestBody TransactionStatusRequest request,
            Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success("Transaction status query submitted",
                mpesaOperationsService.queryTransactionStatus(auth.getName(), request)));
    }

    // ==================== M-PESA REVERSAL ====================

    /**
     * Reverses a completed C2B transaction (refunds the customer, debits our
     * shortcode). If the transactionId matches a completed deposit Transaction
     * on file, that wallet is automatically debited once the reversal
     * succeeds — see MpesaOperationsService.completeOperation.
     * NOTE: B2C payouts cannot be reversed via this API (Safaricom portal only).
     * See https://developer.safaricom.co.ke/apis/Reversal
     */
    @PostMapping("/mpesa/reversal")
    public ResponseEntity<ApiResponse<MpesaAsyncResponse>> reverseTransaction(
            @Valid @RequestBody MpesaReversalRequest request,
            Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success("Reversal submitted",
                mpesaOperationsService.initiateReversal(auth.getName(), request)));
    }

    // ==================== M-PESA OPERATIONS LOOKUP ====================

    /**
     * Polls the stored result of a previously submitted Account Balance,
     * Transaction Status, or Reversal request by the ConversationID returned
     * at submission time.
     */
    @GetMapping("/mpesa/operations/{conversationId}")
    public ResponseEntity<ApiResponse<MpesaOperation>> getMpesaOperation(@PathVariable String conversationId) {
        MpesaOperation operation = mpesaOperationsService.getOperation(conversationId);
        if (operation == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("No operation found for conversationId: " + conversationId));
        }
        return ResponseEntity.ok(ApiResponse.success("Operation retrieved", operation));
    }

    // ==================== M-PESA PULL TRANSACTIONS (C2B RECONCILIATION) ====================

    /**
     * One-time registration of our shortcode for the Pull Transactions API.
     * Safe to call more than once — Safaricom returns ResponseStatus 1001
     * ("already registered") rather than erroring, which is treated as success.
     * See https://developer.safaricom.co.ke/apis/PullTransaction
     */
    @PostMapping("/mpesa/pull/register")
    public ResponseEntity<ApiResponse<PullTransactionResponse>> registerPullTransactions() {
        return ResponseEntity.ok(ApiResponse.success("Pull Transactions registration submitted",
                pullTransactionService.register()));
    }

    /**
     * Pulls C2B transactions for the given window (or the last 24h if
     * startDate/endDate are omitted — Safaricom retains up to 48h) and
     * reconciles each one against local Transaction records, crediting any
     * wallet whose missed C2B confirmation is recovered this way.
     */
    @PostMapping("/mpesa/pull/query")
    public ResponseEntity<ApiResponse<PullTransactionResponse>> queryPullTransactions(
            @RequestBody(required = false) PullTransactionQueryRequest request) {
        PullTransactionResponse result = (request == null
                || request.getStartDate() == null || request.getEndDate() == null)
                ? pullTransactionService.queryAndReconcileDefault()
                : pullTransactionService.queryAndReconcile(request.getStartDate(), request.getEndDate(),
                        request.getOffsetValue() != null ? request.getOffsetValue() : 0);

        return ResponseEntity.ok(ApiResponse.success("Pull Transactions query and reconciliation complete", result));
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