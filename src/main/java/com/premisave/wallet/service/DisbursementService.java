package com.premisave.wallet.service;

import com.premisave.wallet.dto.DisbursementRequest;
import com.premisave.wallet.dto.DisbursementResponse;
import com.premisave.wallet.entity.Disbursement;
import com.premisave.wallet.entity.Transaction;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.DisbursementStatus;
import com.premisave.wallet.enums.TransactionStatus;
import com.premisave.wallet.enums.TransactionType;
import com.premisave.wallet.exception.InsufficientFundsException;
import com.premisave.wallet.exception.WalletFrozenException;
import com.premisave.wallet.exception.WalletNotFoundException;
import com.premisave.wallet.repository.DisbursementRepository;
import com.premisave.wallet.repository.TransactionRepository;
import com.premisave.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DisbursementService {

    private final WalletRepository walletRepository;
    private final DisbursementRepository disbursementRepository;
    private final TransactionRepository transactionRepository;
    private final MpesaService mpesaService;
    private final IdempotencyService idempotencyService;

    @Transactional
    public DisbursementResponse processDisbursement(String userId, DisbursementRequest request) {
        // Idempotency check
        idempotencyService.checkIdempotency(request.getReference()); // Add reference field to DTO if needed

        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for userId: " + userId));

        if (wallet.isFrozen()) {
            throw new WalletFrozenException("Wallet is frozen and cannot disburse funds");
        }

        if (wallet.getBalance().compareTo(request.getAmount()) < 0) {
            throw new InsufficientFundsException("Insufficient funds for disbursement");
        }

        // Deduct balance immediately
        wallet.setBalance(wallet.getBalance().subtract(request.getAmount()));
        walletRepository.save(wallet);

        String reference = request.getReference() != null ? request.getReference() : UUID.randomUUID().toString();

        Disbursement disbursement = new Disbursement();
        disbursement.setUserId(userId);
        disbursement.setWalletId(wallet.getId());
        disbursement.setAmount(request.getAmount());
        disbursement.setDestination(request.getDestination());
        disbursement.setProvider(request.getProvider() != null ? request.getProvider() : "MPESA");
        disbursement.setStatus(DisbursementStatus.PENDING);
        disbursement.setReference(reference);

        // Call external provider (M-Pesa B2C)
        var mpesaResponse = mpesaService.sendB2C(request.getDestination(), request.getAmount());

        if (mpesaResponse.isSuccess()) {
            disbursement.setStatus(DisbursementStatus.SUCCESS);
            disbursement.setProviderReference(mpesaResponse.getConversationId());
        } else {
            // Refund on failure
            wallet.setBalance(wallet.getBalance().add(request.getAmount()));
            walletRepository.save(wallet);
            disbursement.setStatus(DisbursementStatus.FAILED);
            log.warn("Disbursement failed for userId={}, refunding balance", userId);
        }

        disbursementRepository.save(disbursement);

        // Record transaction only on success
        if (disbursement.getStatus() == DisbursementStatus.SUCCESS) {
            Transaction tx = new Transaction();
            tx.setUserId(userId);
            tx.setWalletId(wallet.getId());
            tx.setType(TransactionType.DISBURSEMENT);
            tx.setStatus(TransactionStatus.COMPLETED);
            tx.setAmount(request.getAmount());
            tx.setCurrency(Currency.KES);
            tx.setDescription("Disbursement to " + request.getDestination() +
                    (request.getRemarks() != null ? " - " + request.getRemarks() : ""));
            tx.setReference(reference);
            tx.setProviderReference(mpesaResponse.getConversationId());
            transactionRepository.save(tx);
        }

        return new DisbursementResponse(
                disbursement.getId(),
                disbursement.getStatus().name(),
                mpesaResponse.getMessage());
    }
}