package com.premisave.wallet.service;

import com.premisave.wallet.exception.DuplicateTransactionException;
import com.premisave.wallet.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final TransactionRepository transactionRepository;

    /**
     * Checks if a transaction with the given reference already exists.
     * Throws exception if duplicate is found.
     */
    public void checkIdempotency(String reference) {
        if (reference == null || reference.trim().isEmpty()) {
            return; // No reference = no idempotency check
        }

        if (transactionRepository.existsByReference(reference)) {
            log.warn("Duplicate transaction detected with reference: {}", reference);
            throw new DuplicateTransactionException("Transaction with reference " + reference + " has already been processed");
        }
    }

    /**
     * Checks idempotency and returns true if it's a new transaction
     */
    public boolean isNewTransaction(String reference) {
        if (reference == null || reference.trim().isEmpty()) {
            return true;
        }
        return !transactionRepository.existsByReference(reference);
    }

    /**
     * Record a transaction reference after successful processing (for future use)
     */
    @Transactional
    public void recordTransactionReference(String reference, String transactionId) {
        // Currently we rely on Transaction entity, but this can be extended with a dedicated idempotency log if needed
        log.info("Transaction reference recorded: {} -> TX ID: {}", reference, transactionId);
    }
}