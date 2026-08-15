package com.premisave.wallet.repository;

import com.premisave.wallet.entity.Deposit;
import com.premisave.wallet.enums.DepositStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface DepositRepository extends MongoRepository<Deposit, String> {
    List<Deposit> findByUserIdOrderByCreatedAtDesc(String userId);

    /** Used to reconcile async webhook/callback results back to the deposit that started them. */
    Optional<Deposit> findByProviderReference(String providerReference);

    /** Used to reconcile a webhook keyed by our own idempotency reference rather than the provider's own id (e.g. NOWPayments' order_id). */
    Optional<Deposit> findByReference(String reference);

    /**
     * Used by each provider's stuck-deposit sweeper (e.g.
     * autoFailStuckNowPaymentsDeposits, autoFailStuckStripeDeposits) — now
     * a real provider-filtered query instead of the previous string-prefix
     * matching against Transaction.description, which was flagged as
     * fragile in both of those methods' javadoc at the time they were
     * written, since Transaction has no provider field at all.
     */
    List<Deposit> findByProviderAndStatusAndCreatedAtBefore(String provider, DepositStatus status, LocalDateTime cutoff);

    /**
     * Used by IdempotencyService to detect a duplicate deposit request —
     * same reasoning as DisbursementRepository.existsByReference: a
     * Deposit record now exists immediately at initiation (status
     * PENDING), separate from the Transaction row only created later once
     * SUCCESS is confirmed.
     */
    boolean existsByReference(String reference);
}