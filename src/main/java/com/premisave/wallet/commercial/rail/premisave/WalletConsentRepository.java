package com.premisave.wallet.commercial.rail.premisave;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface WalletConsentRepository extends MongoRepository<WalletConsent, String> {

    Optional<WalletConsent> findByTransactionId(String transactionId);

    Optional<WalletConsent> findByPaymentMethodId(String paymentMethodId);

    List<WalletConsent> findByAccountNumberAndStatusInOrderByCreatedAtDesc(String accountNumber, Collection<WalletConsent.Status> statuses);
}
