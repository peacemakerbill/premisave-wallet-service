package com.premisave.wallet.commercial.repository;

import com.premisave.wallet.commercial.entity.MerchantHold;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface MerchantHoldRepository extends MongoRepository<MerchantHold, String> {
    Optional<MerchantHold> findByTransactionId(String transactionId);
}
