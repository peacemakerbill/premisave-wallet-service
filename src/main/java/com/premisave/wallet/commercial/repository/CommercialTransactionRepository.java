package com.premisave.wallet.commercial.repository;

import com.premisave.wallet.commercial.entity.CommercialTransaction;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface CommercialTransactionRepository extends MongoRepository<CommercialTransaction, String> {
    Optional<CommercialTransaction> findByIdAndMerchantIdAndLivemode(String id, String merchantId, boolean livemode);
}
