package com.premisave.wallet.service;

import com.premisave.wallet.dto.*;
import com.premisave.wallet.entity.Transaction;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.exception.InsufficientFundsException;
import com.premisave.wallet.exception.WalletFrozenException;
import com.premisave.wallet.exception.WalletNotFoundException;
import com.premisave.wallet.repository.TransactionRepository;
import com.premisave.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class WalletService {

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;

    /**
     * Get wallet by account number (email)
     */
    public WalletResponse getWallet(String accountNumber) {
        Wallet wallet = walletRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for: " + accountNumber));
        return mapToResponse(wallet);
    }

    /**
     * Get wallet balance
     */
    public WalletBalanceResponse getBalance(String accountNumber) {
        Wallet wallet = walletRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for: " + accountNumber));
        return new WalletBalanceResponse(wallet.getBalance(), wallet.getCurrency().name(), wallet.isFrozen());
    }

    /**
     * Create wallet if it doesn't exist
     */
    @Transactional
    public WalletResponse createWallet(String userId, String email) {
        Optional<Wallet> existing = walletRepository.findByAccountNumber(email);
        if (existing.isPresent()) {
            log.info("Wallet already exists for email: {}", email);
            return mapToResponse(existing.get());
        }

        Wallet wallet = new Wallet();
        wallet.setUserId(userId);
        wallet.setAccountNumber(email);
        wallet.setBalance(BigDecimal.ZERO);
        wallet.setCurrency(Currency.KES);
        wallet.setFrozen(false);

        wallet = walletRepository.save(wallet);
        log.info("Successfully created wallet for userId={} | email={}", userId, email);

        return mapToResponse(wallet);
    }

    /**
     * Freeze wallet
     */
    @Transactional
    public WalletResponse freezeWallet(String userId) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for userId: " + userId));

        if (wallet.isFrozen()) {
            log.warn("Wallet is already frozen for userId: {}", userId);
            return mapToResponse(wallet);
        }

        wallet.setFrozen(true);
        wallet = walletRepository.save(wallet);
        log.info("Wallet frozen for userId: {}", userId);

        return mapToResponse(wallet);
    }

    /**
     * Unfreeze wallet
     */
    @Transactional
    public WalletResponse unfreezeWallet(String userId) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for userId: " + userId));

        if (!wallet.isFrozen()) {
            log.warn("Wallet is already active for userId: {}", userId);
            return mapToResponse(wallet);
        }

        wallet.setFrozen(false);
        wallet = walletRepository.save(wallet);
        log.info("Wallet unfrozen for userId: {}", userId);

        return mapToResponse(wallet);
    }

    /**
     * Get detailed wallet statement with summary
     */
    public WalletStatementResponse getStatement(String email, WalletStatementRequest request) {
        Wallet wallet = walletRepository.findByAccountNumber(email)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for email: " + email));

        LocalDate toDate = request.getToDate() != null ? request.getToDate() : LocalDate.now();

        // Fetch all transactions for the user
        List<Transaction> allTransactions = transactionRepository
                .findByUserIdOrderByCreatedAtDesc(wallet.getUserId());

        // Filter by date range
        List<TransactionResponse> filteredTransactions = allTransactions.stream()
                .filter(tx -> {
                    LocalDate txDate = tx.getCreatedAt().toLocalDate();
                    return !txDate.isBefore(request.getFromDate()) && !txDate.isAfter(toDate);
                })
                .filter(tx -> request.getType() == null || tx.getType().name().equals(request.getType()))
                .map(this::mapToTransactionResponse)
                .collect(Collectors.toList());

        // Calculate totals
        BigDecimal totalCredits = filteredTransactions.stream()
                .filter(t -> t.getAmount().compareTo(BigDecimal.ZERO) > 0)
                .map(TransactionResponse::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalDebits = filteredTransactions.stream()
                .filter(t -> t.getAmount().compareTo(BigDecimal.ZERO) < 0)
                .map(TransactionResponse::getAmount)
                .map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new WalletStatementResponse(
                wallet.getAccountNumber(),
                wallet.getBalance().subtract(totalCredits).add(totalDebits), // Approximate opening balance
                wallet.getBalance(),
                totalCredits,
                totalDebits,
                request.getFromDate(),
                toDate,
                filteredTransactions
        );
    }

    /**
     * Helper method to check if wallet is active and has sufficient funds
     */
    public void validateWalletForTransaction(String userId, BigDecimal amount) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for userId: " + userId));

        if (wallet.isFrozen()) {
            throw new WalletFrozenException("Wallet is frozen and cannot perform transactions");
        }

        if (wallet.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException("Insufficient balance. Available: " + wallet.getBalance());
        }
    }

    private TransactionResponse mapToTransactionResponse(Transaction tx) {
        return new TransactionResponse(
                tx.getId(),
                tx.getType(),
                tx.getStatus(),
                tx.getAmount(),
                tx.getCurrency() != null ? tx.getCurrency().name() : "KES",
                tx.getDescription(),
                tx.getCreatedAt()
        );
    }

    private WalletResponse mapToResponse(Wallet wallet) {
        WalletResponse response = new WalletResponse();
        response.setId(wallet.getId());
        response.setAccountNumber(wallet.getAccountNumber());
        response.setUserId(wallet.getUserId());
        response.setBalance(wallet.getBalance());
        response.setCurrency(wallet.getCurrency());
        response.setFrozen(wallet.isFrozen());
        return response;
    }
}