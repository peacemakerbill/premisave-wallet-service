package com.premisave.wallet.service;

import com.premisave.wallet.dto.*;
import com.premisave.wallet.entity.Disbursement;
import com.premisave.wallet.entity.Transaction;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.enums.DisbursementStatus;
import com.premisave.wallet.enums.TransactionStatus;
import com.premisave.wallet.enums.TransactionType;
import com.premisave.wallet.exception.WalletNotFoundException;
import com.premisave.wallet.repository.DisbursementRepository;
import com.premisave.wallet.repository.TransactionRepository;
import com.premisave.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminWalletService {

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final DisbursementRepository disbursementRepository;
    private final WalletService walletService; // Reuse existing logic

    public Page<WalletResponse> getAllWallets(Pageable pageable) {
        return walletRepository.findAll(pageable).map(this::mapToWalletResponse);
    }

    public List<WalletResponse> searchWallets(String query) {
        // Simple search by accountNumber or userId
        List<Wallet> wallets = walletRepository.findAll().stream()
                .filter(w -> w.getAccountNumber().contains(query) || w.getUserId().contains(query))
                .toList();
        return wallets.stream().map(this::mapToWalletResponse).toList();
    }

    public WalletResponse getWalletByUserId(String userId) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for userId: " + userId));
        return mapToWalletResponse(wallet);
    }

    public WalletResponse freezeWallet(String userId) {
        return walletService.freezeWallet(userId);
    }

    public WalletResponse unfreezeWallet(String userId) {
        return walletService.unfreezeWallet(userId);
    }

    @Transactional
    public PaymentResponse creditWallet(String userId, ManualAdjustmentRequest request) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found"));

        wallet.setBalance(wallet.getBalance().add(request.getAmount()));
        walletRepository.save(wallet);

        Transaction tx = createAdjustmentTransaction(wallet, TransactionType.DEPOSIT, request.getAmount(),
                "Admin Credit: " + request.getReason(), request.getReference());
        transactionRepository.save(tx);

        log.info("Admin credited wallet {} with {} - Reason: {}", userId, request.getAmount(), request.getReason());
        return new PaymentResponse(true, tx.getId(), "Credit successful");
    }

    @Transactional
    public PaymentResponse debitWallet(String userId, ManualAdjustmentRequest request) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found"));

        if (wallet.getBalance().compareTo(request.getAmount()) < 0) {
            throw new com.premisave.wallet.exception.InsufficientFundsException("Insufficient balance for debit");
        }

        wallet.setBalance(wallet.getBalance().subtract(request.getAmount()));
        walletRepository.save(wallet);

        Transaction tx = createAdjustmentTransaction(wallet, TransactionType.WITHDRAWAL, request.getAmount().negate(),
                "Admin Debit: " + request.getReason(), request.getReference());
        transactionRepository.save(tx);

        log.info("Admin debited wallet {} with {} - Reason: {}", userId, request.getAmount(), request.getReason());
        return new PaymentResponse(true, tx.getId(), "Debit successful");
    }

    public Page<TransactionResponse> getAllTransactions(String userId, TransactionType type,
                                                        TransactionStatus status, LocalDate fromDate,
                                                        LocalDate toDate, Pageable pageable) {
        // Simplified for now - you can extend with custom queries
        List<Transaction> txs = transactionRepository.findByUserIdOrderByCreatedAtDesc(userId != null ? userId : "");
        // Add filtering logic as needed
        return /* convert to page */ null; // TODO: Implement full pagination with filters
    }

    public TransactionResponse getTransactionById(String transactionId) {
        Transaction tx = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new RuntimeException("Transaction not found"));
        return mapToTransactionResponse(tx);
    }

    public List<Disbursement> getPendingDisbursements() {
        return disbursementRepository.findAll().stream()
                .filter(d -> d.getStatus() == DisbursementStatus.PENDING)
                .toList();
    }

    @Transactional
    public DisbursementResponse approveDisbursement(String disbursementId) {
        // Implementation depends on your M-Pesa B2C logic
        log.info("Disbursement {} approved by admin", disbursementId);
        return new DisbursementResponse(disbursementId, "SUCCESS", "Approved by admin");
    }

    public DisbursementResponse rejectDisbursement(String disbursementId, String reason) {
        log.info("Disbursement {} rejected. Reason: {}", disbursementId, reason);
        return new DisbursementResponse(disbursementId, "FAILED", "Rejected: " + reason);
    }

    public Map<String, Object> getSystemSummary() {
        Map<String, Object> summary = new HashMap<>();
        summary.put("totalWallets", walletRepository.count());
        summary.put("totalBalance", walletRepository.findAll().stream()
                .map(Wallet::getBalance).reduce(BigDecimal.ZERO, BigDecimal::add));
        summary.put("totalTransactions", transactionRepository.count());
        return summary;
    }

    public Map<String, Object> getDailyReport(LocalDate date) {
        // Placeholder
        return Map.of("date", date, "totalDeposits", BigDecimal.ZERO, "totalWithdrawals", BigDecimal.ZERO);
    }

    public Map<String, BigDecimal> getTotalBalanceOverview() {
        BigDecimal total = walletRepository.findAll().stream()
                .map(Wallet::getBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return Map.of("totalSystemBalance", total);
    }

    private Transaction createAdjustmentTransaction(Wallet wallet, TransactionType type, BigDecimal amount,
                                                    String description, String reference) {
        Transaction tx = new Transaction();
        tx.setUserId(wallet.getUserId());
        tx.setWalletId(wallet.getId());
        tx.setType(type);
        tx.setStatus(TransactionStatus.COMPLETED);
        tx.setAmount(amount);
        tx.setDescription(description);
        tx.setReference(reference != null ? reference : "ADMIN-ADJ-" + System.currentTimeMillis());
        return tx;
    }

    private WalletResponse mapToWalletResponse(Wallet wallet) {
        WalletResponse response = new WalletResponse();
        response.setId(wallet.getId());
        response.setAccountNumber(wallet.getAccountNumber());
        response.setUserId(wallet.getUserId());
        response.setBalance(wallet.getBalance());
        response.setCurrency(wallet.getCurrency());
        response.setFrozen(wallet.isFrozen());
        return response;
    }

    private TransactionResponse mapToTransactionResponse(Transaction tx) {
        return new TransactionResponse(
                tx.getId(), tx.getType(), tx.getStatus(), tx.getAmount(),
                tx.getCurrency() != null ? tx.getCurrency().name() : "KES",
                tx.getDescription(), tx.getCreatedAt()
        );
    }
}