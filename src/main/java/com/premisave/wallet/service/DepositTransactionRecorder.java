package com.premisave.wallet.service;

import com.premisave.wallet.entity.Deposit;
import com.premisave.wallet.entity.Transaction;
import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.TransactionStatus;
import com.premisave.wallet.enums.TransactionType;
import com.premisave.wallet.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Records the Transaction row for a confirmed deposit — mirrors
 * DisbursementTransactionRecorder exactly, on the opposite side of the
 * wallet. Kept as its own small shared service (rather than inline logic
 * duplicated in each provider's deposit service) for the same reason:
 * every provider-specific deposit service's completion handler needs it
 * independently, without creating a circular dependency on some larger
 * shared class.
 */
@Service
@RequiredArgsConstructor
public class DepositTransactionRecorder {

    private final TransactionRepository transactionRepository;

    public void record(String userId, String walletId, BigDecimal amount,
                        Deposit deposit, String reference) {
        Transaction tx = new Transaction();
        tx.setUserId(userId);
        tx.setWalletId(walletId);
        tx.setType(TransactionType.DEPOSIT);
        tx.setStatus(TransactionStatus.COMPLETED);
        tx.setAmount(amount);
        tx.setCurrency(Currency.KES);
        tx.setDescription("Deposit via " + deposit.getProvider());
        tx.setReference(reference);
        tx.setProviderReference(deposit.getProviderReference());
        transactionRepository.save(tx);
    }
}