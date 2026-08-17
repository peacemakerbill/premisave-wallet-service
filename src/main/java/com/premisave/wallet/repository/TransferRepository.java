package com.premisave.wallet.repository;

import com.premisave.wallet.entity.Transfer;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface TransferRepository extends MongoRepository<Transfer, String> {
    List<Transfer> findBySenderIdOrderByCreatedAtDesc(String senderId);
    List<Transfer> findByRecipientIdOrderByCreatedAtDesc(String recipientId);

    /** Used to reconcile a transfer back from its own idempotency reference. */
    Optional<Transfer> findByReference(String reference);

    /** Used by IdempotencyService to detect a duplicate transfer request before any wallet is touched. */
    boolean existsByReference(String reference);
}