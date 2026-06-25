package com.premisave.wallet.service;

import com.premisave.wallet.dto.PaymentInitiateRequest;
import com.premisave.wallet.dto.PaymentResponse;
import com.premisave.wallet.entity.Transaction;
import com.premisave.wallet.enums.TransactionStatus;
import com.premisave.wallet.enums.TransactionType;
import com.premisave.wallet.repository.TransactionRepository;
import com.premisave.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    @Transactional
    public PaymentResponse deductFromWallet(String userId, PaymentInitiateRequest request) {
        var wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("Wallet not found"));

        if (wallet.isFrozen()) {
            throw new RuntimeException("Wallet is frozen");
        }
        if (wallet.getBalance().compareTo(request.getAmount()) < 0) {
            throw new RuntimeException("Insufficient balance");
        }

        // Deduct
        wallet.setBalance(wallet.getBalance().subtract(request.getAmount()));
        walletRepository.save(wallet);

        // Record transaction
        Transaction tx = new Transaction();
        tx.setUserId(userId);
        tx.setWalletId(wallet.getId());
        tx.setType(TransactionType.PAYMENT);
        tx.setStatus(TransactionStatus.COMPLETED);
        tx.setAmount(request.getAmount());
        tx.setDescription("Payment to " + request.getService());
        tx.setReference(request.getReference());
        transactionRepository.save(tx);

        return new PaymentResponse(true, tx.getId(), "Payment successful");
    }
}