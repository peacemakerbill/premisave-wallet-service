package com.premisave.wallet.commercial.batch;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface PayoutBatchRepository extends MongoRepository<PayoutBatch, String> {
    Optional<PayoutBatch> findByBatchIdAndMerchantIdAndLivemode(String batchId, String merchantId, boolean livemode);
}
