package com.premisave.wallet.service;

import com.premisave.wallet.dto.*;
import com.premisave.wallet.entity.Transaction;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.exception.InsufficientFundsException;
import com.premisave.wallet.exception.WalletAlreadyExistsException;
import com.premisave.wallet.exception.WalletAlreadyFrozenException;
import com.premisave.wallet.exception.WalletFrozenException;
import com.premisave.wallet.exception.WalletNotFoundException;
import com.premisave.wallet.exception.WalletNotFrozenException;
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
    private final MpesaService mpesaService;

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
     * Create wallet if it doesn't exist.
     *
     * Rejects outright if a wallet already exists — checked by BOTH
     * accountNumber (email) and userId, since a mismatch between the two
     * would otherwise let a caller end up owning two wallet records (e.g.
     * userId already has a wallet under a different/older email). Callers
     * should GET /wallet instead of re-POSTing /wallet/create.
     */
    @Transactional
    public WalletResponse createWallet(String userId, String email) {
        Optional<Wallet> existingByEmail = walletRepository.findByAccountNumber(email);
        if (existingByEmail.isPresent()) {
            log.warn("Wallet creation rejected — wallet already exists for email={}", email);
            throw new WalletAlreadyExistsException(
                    "You already have a wallet for this account. Please refresh the page to view it.");
        }

        Optional<Wallet> existingByUserId = walletRepository.findByUserId(userId);
        if (existingByUserId.isPresent()) {
            log.warn("Wallet creation rejected — wallet already exists for userId={}", userId);
            throw new WalletAlreadyExistsException(
                    "You already have a wallet. Please refresh the page to view it.");
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
     * Freeze wallet.
     *
     * Rejects if the wallet is already frozen — callers get an explicit,
     * actionable error instead of a silent no-op that looks successful.
     */
    @Transactional
    public WalletResponse freezeWallet(String userId) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for userId: " + userId));

        if (wallet.isFrozen()) {
            log.warn("Freeze rejected — wallet already frozen for userId: {}", userId);
            throw new WalletAlreadyFrozenException("This wallet is already frozen.");
        }

        wallet.setFrozen(true);
        wallet = walletRepository.save(wallet);
        log.info("Wallet frozen for userId: {}", userId);

        return mapToResponse(wallet);
    }

    /**
     * Unfreeze wallet.
     *
     * Rejects if the wallet is not currently frozen — same reasoning as
     * freezeWallet above: an explicit error beats a silent no-op.
     */
    @Transactional
    public WalletResponse unfreezeWallet(String userId) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for userId: " + userId));

        if (!wallet.isFrozen()) {
            log.warn("Unfreeze rejected — wallet is not frozen for userId: {}", userId);
            throw new WalletNotFrozenException("This wallet is not frozen — nothing to unfreeze.");
        }

        wallet.setFrozen(false);
        wallet = walletRepository.save(wallet);
        log.info("Wallet unfrozen for userId: {}", userId);

        return mapToResponse(wallet);
    }

    /**
     * Get detailed wallet statement with summary.
     * Read-only — intentionally allowed even while frozen, so a frozen
     * user can still see their transaction history and understand their
     * balance; only money-movement and payout-destination changes are
     * blocked while frozen (see validateWalletForTransaction,
     * updatePaypalEmail, updateMpesaPhoneNumber).
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
     * Helper method to check if wallet is active and has sufficient funds.
     * Used by every money-movement flow (PaymentService, TransferService,
     * DisbursementService) — the single source of truth for "can this
     * wallet spend right now."
     */
    public void validateWalletForTransaction(String userId, BigDecimal amount) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for userId: " + userId));

        if (wallet.isFrozen()) {
            throw new WalletFrozenException("Wallet is frozen and cannot perform transactions until it is unfrozen");
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

    /**
     * Sets/updates the PayPal payout email.
     * Blocked while frozen — same reasoning as blocking transfers/
     * disbursements: a frozen wallet's money-movement configuration
     * shouldn't be changeable either (prevents a compromised/flagged
     * account from redirecting future payouts while under review).
     */
    @Transactional
    public WalletResponse updatePaypalEmail(String userId, String paypalEmail) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for userId: " + userId));

        if (wallet.isFrozen()) {
            throw new WalletFrozenException(
                    "Wallet is frozen — payout details cannot be changed until it is unfrozen");
        }

        wallet.setPaypalEmail(paypalEmail);
        wallet = walletRepository.save(wallet);
        log.info("PayPal email updated for userId={}", userId);
        return mapToResponse(wallet);
    }

    /**
     * Sets/updates the M-Pesa phone number used for quick deposits (STK
     * push — no need to type a number every time) and disbursements.
     * Resolved authoritatively from here by DepositService/
     * DisbursementService — never taken from a deposit/disbursement
     * request itself — same reasoning as the PayPal email pattern above
     * (eliminates typo/mistargeted-payout risk).
     * Blocked while frozen — same reasoning as updatePaypalEmail above.
     */
    @Transactional
    public WalletResponse updateMpesaPhoneNumber(String userId, String phoneNumber) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for userId: " + userId));

        if (wallet.isFrozen()) {
            throw new WalletFrozenException(
                    "Wallet is frozen — payout details cannot be changed until it is unfrozen");
        }

        wallet.setMpesaPhoneNumber(mpesaService.normalizePhone(phoneNumber));
        wallet = walletRepository.save(wallet);
        log.info("M-Pesa phone number updated for userId={}", userId);
        return mapToResponse(wallet);
    }

    private WalletResponse mapToResponse(Wallet wallet) {
        WalletResponse response = new WalletResponse();
        response.setId(wallet.getId());
        response.setAccountNumber(wallet.getAccountNumber());
        response.setUserId(wallet.getUserId());
        response.setBalance(wallet.getBalance());
        response.setCurrency(wallet.getCurrency());
        response.setFrozen(wallet.isFrozen());
        response.setPaypalEmail(wallet.getPaypalEmail());
        response.setMpesaPhoneNumber(wallet.getMpesaPhoneNumber());
        response.setHasSavedCard(wallet.getStripeDefaultPaymentMethodId() != null);
        response.setCardBrand(wallet.getStripeCardBrand());
        response.setCardLast4(wallet.getStripeCardLast4());
        return response;
    }
}