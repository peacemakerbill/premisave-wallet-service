package com.premisave.wallet.controller;

import com.premisave.wallet.dto.ApiResponse;
import com.premisave.wallet.dto.DepositRecordResponse;
import com.premisave.wallet.dto.DisbursementRecordResponse;
import com.premisave.wallet.dto.PaymentRecordResponse;
import com.premisave.wallet.dto.TransferRecordResponse;
import com.premisave.wallet.entity.CompanyLedgerEntry;
import com.premisave.wallet.service.CommissionService;
import com.premisave.wallet.service.DepositService;
import com.premisave.wallet.service.DisbursementService;
import com.premisave.wallet.service.PaymentService;
import com.premisave.wallet.service.TransferService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only, cross-user visibility into every financial entity built
 * alongside tonight's Deposit/Disbursement/Transfer/Payment/
 * CompanyLedgerEntry work — Deposit, Disbursement, Transfer, Payment, and
 * the company P&L ledger.
 *
 * Deliberately a SEPARATE controller from AdminWalletController, not an
 * addition to it — AdminWalletController's own service
 * (AdminWalletService) was never actually seen in this session, only
 * called into from that controller's existing methods. Extending it
 * blind would mean guessing at its real constructor dependencies, import
 * list, and existing multi-field filtering implementation, none of which
 * were ever verified. This controller instead calls straight into the
 * same DepositService/DisbursementService/TransferService/PaymentService/
 * CommissionService already built and verified earlier tonight — no new
 * service logic invented, just new admin-facing entry points onto it.
 *
 * Same security posture as AdminWalletController — ADMIN/FINANCE/OPERATIONS
 * only.
 *
 * Pagination follows the exact convention AdminWalletController's own
 * GET /admin/wallet/transactions already uses — a bare Pageable parameter,
 * resolved automatically from query params like ?page=0&size=20&sort=createdAt,desc.
 */
@RestController
@RequestMapping("/admin/finance")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'FINANCE', 'OPERATIONS')")
public class AdminFinanceController {

    private final DepositService depositService;
    private final DisbursementService disbursementService;
    private final TransferService transferService;
    private final PaymentService paymentService;
    private final CommissionService commissionService;

    @GetMapping("/deposits")
    public ResponseEntity<ApiResponse<Page<DepositRecordResponse>>> getAllDeposits(Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success("Deposits retrieved", depositService.getAllDeposits(pageable)));
    }

    @GetMapping("/disbursements")
    public ResponseEntity<ApiResponse<Page<DisbursementRecordResponse>>> getAllDisbursements(Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success("Disbursements retrieved", disbursementService.getAllDisbursements(pageable)));
    }

    @GetMapping("/transfers")
    public ResponseEntity<ApiResponse<Page<TransferRecordResponse>>> getAllTransfers(Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success("Transfers retrieved", transferService.getAllTransfers(pageable)));
    }

    @GetMapping("/payments")
    public ResponseEntity<ApiResponse<Page<PaymentRecordResponse>>> getAllPayments(Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success("Payments retrieved", paymentService.getAllPayments(pageable)));
    }

    /**
     * The company profit/loss ledger — every commission entry recorded
     * from transfers and gateway disbursements since this system existed,
     * previously visible only via direct MongoDB query.
     */
    @GetMapping("/ledger")
    public ResponseEntity<ApiResponse<Page<CompanyLedgerEntry>>> getLedger(Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success("Company ledger retrieved", commissionService.getAllLedgerEntries(pageable)));
    }
}