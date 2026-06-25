package com.premisave.wallet.service;

import com.premisave.wallet.dto.PaymentInitiateRequest;
import com.premisave.wallet.dto.PaymentResponse;
import com.premisave.wallet.entity.Transaction;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.enums.TransactionStatus;
import com.premisave.wallet.enums.TransactionType;
import com.premisave.wallet.exception.DuplicateTransactionException;
import com.premisave.wallet.exception.InsufficientFundsException;
import com.premisave.wallet.exception.WalletFrozenException;
import com.premisave.wallet.exception.WalletNotFoundException;
import com.premisave.wallet.repository.TransactionRepository;
import com.premisave.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;

    @Transactional
    public PaymentResponse deductFromWallet(String userId, PaymentInitiateRequest request) {
        // Idempotency check
        if (request.getReference() != null) {
            boolean exists = transactionRepository
                    .findByUserIdOrderByCreatedAtDesc(userId)
                    .stream()
                    .anyMatch(tx -> request.getReference().equals(tx.getReference()));
            if (exists) {
                throw new DuplicateTransactionException(
                        "Transaction with reference " + request.getReference() + " already processed");
            }
        }

        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for userId: " + userId));

        if (wallet.isFrozen()) {
            throw new WalletFrozenException("Wallet is frozen");
        }

        if (wallet.getBalance().compareTo(request.getAmount()) < 0) {
            throw new InsufficientFundsException("Insufficient balance");
        }

        wallet.setBalance(wallet.getBalance().subtract(request.getAmount()));
        walletRepository.save(wallet);

        Transaction tx = new Transaction();
        tx.setUserId(userId);
        tx.setWalletId(wallet.getId());
        tx.setType(TransactionType.PAYMENT);
        tx.setStatus(TransactionStatus.COMPLETED);
        tx.setAmount(request.getAmount());
        tx.setDescription("Payment to " + request.getService());
        tx.setReference(request.getReference());
        transactionRepository.save(tx);

        log.info("Payment processed: userId={} amount={} service={}", userId, request.getAmount(), request.getService());
        return new PaymentResponse(true, tx.getId(), "Payment successful");
    }
}