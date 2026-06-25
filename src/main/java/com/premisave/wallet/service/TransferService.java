package com.premisave.wallet.service;

import com.premisave.wallet.dto.TransferRequest;
import com.premisave.wallet.dto.PaymentResponse;
import com.premisave.wallet.entity.Transaction;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.enums.TransactionStatus;
import com.premisave.wallet.enums.TransactionType;
import com.premisave.wallet.exception.InsufficientFundsException;
import com.premisave.wallet.exception.WalletFrozenException;
import com.premisave.wallet.exception.WalletNotFoundException;
import com.premisave.wallet.repository.TransactionRepository;
import com.premisave.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransferService {

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;

    @Transactional
    public PaymentResponse transfer(String senderUserId, TransferRequest request) {
        Wallet sender = walletRepository.findByUserId(senderUserId)
                .orElseThrow(() -> new WalletNotFoundException("Sender wallet not found"));

        Wallet recipient = walletRepository.findByAccountNumber(request.getRecipientAccountNumber())
                .orElseThrow(() -> new WalletNotFoundException(
                        "Recipient wallet not found: " + request.getRecipientAccountNumber()));

        if (sender.getId().equals(recipient.getId())) {
            throw new IllegalArgumentException("Cannot transfer to your own wallet");
        }

        if (sender.isFrozen()) {
            throw new WalletFrozenException("Your wallet is frozen and cannot send funds");
        }

        if (recipient.isFrozen()) {
            throw new WalletFrozenException("Recipient wallet is currently frozen");
        }

        if (sender.getBalance().compareTo(request.getAmount()) < 0) {
            throw new InsufficientFundsException("Insufficient balance for transfer");
        }

        String reference = UUID.randomUUID().toString();

        // Debit sender
        sender.setBalance(sender.getBalance().subtract(request.getAmount()));
        walletRepository.save(sender);

        // Credit recipient
        recipient.setBalance(recipient.getBalance().add(request.getAmount()));
        walletRepository.save(recipient);

        // Record debit transaction
        Transaction debit = buildTransaction(senderUserId, sender.getId(),
                TransactionType.TRANSFER, TransactionStatus.COMPLETED,
                request.getAmount().negate(),
                "Transfer to " + request.getRecipientAccountNumber()
                        + (request.getDescription() != null ? " - " + request.getDescription() : ""),
                reference);
        transactionRepository.save(debit);

        // Record credit transaction
        Transaction credit = buildTransaction(recipient.getUserId(), recipient.getId(),
                TransactionType.TRANSFER, TransactionStatus.COMPLETED,
                request.getAmount(),
                "Transfer from " + sender.getAccountNumber()
                        + (request.getDescription() != null ? " - " + request.getDescription() : ""),
                reference);
        transactionRepository.save(credit);

        log.info("Transfer completed: {} -> {} | Amount: {} | Ref: {}",
                sender.getAccountNumber(), recipient.getAccountNumber(),
                request.getAmount(), reference);

        return new PaymentResponse(true, reference, "Transfer successful");
    }

    private Transaction buildTransaction(String userId, String walletId,
                                         TransactionType type, TransactionStatus status,
                                         java.math.BigDecimal amount, String description,
                                         String reference) {
        Transaction tx = new Transaction();
        tx.setUserId(userId);
        tx.setWalletId(walletId);
        tx.setType(type);
        tx.setStatus(status);
        tx.setAmount(amount);
        tx.setDescription(description);
        tx.setReference(reference);
        return tx;
    }
}