package com.premisave.wallet.service;

import com.premisave.wallet.dto.PaymentInitiateRequest;
import com.premisave.wallet.dto.PaymentResponse;
import com.premisave.wallet.entity.Transaction;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.TransactionStatus;
import com.premisave.wallet.enums.TransactionType;
import com.premisave.wallet.exception.DuplicateTransactionException;
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
    private final WalletService walletService;

    @Transactional
    public PaymentResponse deductFromWallet(String userId, PaymentInitiateRequest request) {
        // Strong idempotency check using repository method
        if (request.getReference() != null && transactionRepository.existsByReference(request.getReference())) {
            throw new DuplicateTransactionException(
                    "Transaction with reference " + request.getReference() + " already processed");
        }

        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for userId: " + userId));

        // Use shared validation
        walletService.validateWalletForTransaction(userId, request.getAmount());

        wallet.setBalance(wallet.getBalance().subtract(request.getAmount()));
        walletRepository.save(wallet);

        Transaction tx = new Transaction();
        tx.setUserId(userId);
        tx.setWalletId(wallet.getId());
        tx.setType(TransactionType.PAYMENT);
        tx.setStatus(TransactionStatus.COMPLETED);
        tx.setAmount(request.getAmount());
        tx.setCurrency(Currency.KES);
        tx.setDescription("Payment to " + request.getService());
        tx.setReference(request.getReference());
        transactionRepository.save(tx);

        log.info("Payment processed successfully: userId={} amount={} service={} ref={}",
                userId, request.getAmount(), request.getService(), request.getReference());

        return new PaymentResponse(true, tx.getId(), "Payment successful");
    }
}