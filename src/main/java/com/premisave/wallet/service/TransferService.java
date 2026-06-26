package com.premisave.wallet.service;

import com.premisave.wallet.dto.PaymentResponse;
import com.premisave.wallet.dto.TransferRequest;
import com.premisave.wallet.entity.Transaction;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.TransactionStatus;
import com.premisave.wallet.enums.TransactionType;
import com.premisave.wallet.exception.DuplicateTransactionException;
import com.premisave.wallet.exception.WalletFrozenException;
import com.premisave.wallet.exception.WalletNotFoundException;
import com.premisave.wallet.repository.TransactionRepository;
import com.premisave.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransferService {

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final WalletService walletService;

    @Transactional
    public PaymentResponse transfer(String senderUserId, TransferRequest request) {
        // Idempotency check
        if (request.getReference() != null && transactionRepository.existsByReference(request.getReference())) {
            throw new DuplicateTransactionException("Transaction with reference " + request.getReference() + " already exists");
        }

        Wallet sender = walletRepository.findByUserId(senderUserId)
                .orElseThrow(() -> new WalletNotFoundException("Sender wallet not found for userId: " + senderUserId));

        Wallet recipient = walletRepository.findByAccountNumber(request.getRecipientAccountNumber())
                .orElseThrow(() -> new WalletNotFoundException(
                        "Recipient wallet not found for account: " + request.getRecipientAccountNumber()));

        if (sender.getId().equals(recipient.getId())) {
            throw new IllegalArgumentException("Cannot transfer funds to your own wallet");
        }

        // Centralized validation (checks frozen status + sufficient balance)
        walletService.validateWalletForTransaction(senderUserId, request.getAmount());

        if (recipient.isFrozen()) {
            throw new WalletFrozenException("Recipient wallet is currently frozen and cannot receive funds");
        }

        String reference = request.getReference() != null ? 
                request.getReference() : UUID.randomUUID().toString();

        // Perform the transfer
        sender.setBalance(sender.getBalance().subtract(request.getAmount()));
        recipient.setBalance(recipient.getBalance().add(request.getAmount()));

        walletRepository.save(sender);
        walletRepository.save(recipient);

        // Record debit transaction for sender
        Transaction debit = buildTransaction(
                senderUserId, sender.getId(), TransactionType.TRANSFER, TransactionStatus.COMPLETED,
                request.getAmount().negate(),
                "Transfer to " + request.getRecipientAccountNumber() +
                        (request.getDescription() != null ? " - " + request.getDescription() : ""),
                reference
        );

        // Record credit transaction for recipient
        Transaction credit = buildTransaction(
                recipient.getUserId(), recipient.getId(), TransactionType.TRANSFER, TransactionStatus.COMPLETED,
                request.getAmount(),
                "Transfer from " + sender.getAccountNumber() +
                        (request.getDescription() != null ? " - " + request.getDescription() : ""),
                reference
        );

        transactionRepository.save(debit);
        transactionRepository.save(credit);

        log.info("Transfer completed successfully | Sender: {} | Recipient: {} | Amount: {} | Ref: {}",
                sender.getAccountNumber(), recipient.getAccountNumber(), request.getAmount(), reference);

        return new PaymentResponse(true, reference, "Transfer successful");
    }

    private Transaction buildTransaction(String userId, String walletId,
                                         TransactionType type, TransactionStatus status,
                                         BigDecimal amount, String description,
                                         String reference) {
        Transaction tx = new Transaction();
        tx.setUserId(userId);
        tx.setWalletId(walletId);
        tx.setType(type);
        tx.setStatus(status);
        tx.setAmount(amount);
        tx.setCurrency(Currency.KES);
        tx.setDescription(description);
        tx.setReference(reference);
        return tx;
    }
}