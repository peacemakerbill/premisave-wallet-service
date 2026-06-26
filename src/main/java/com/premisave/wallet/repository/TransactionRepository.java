package com.premisave.wallet.repository;

import com.premisave.wallet.entity.Transaction;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TransactionRepository extends MongoRepository<Transaction, String> {

    List<Transaction> findByUserIdOrderByCreatedAtDesc(String userId);

    /** Used by IdempotencyService to detect duplicate references. */
    boolean existsByReference(String reference);

    /**
     * Used by MpesaC2BService to detect duplicate M-Pesa TransIDs.
     * providerReference stores the M-Pesa TransID (e.g. "RCA71X5MJ4").
     */
    boolean existsByProviderReference(String providerReference);
}