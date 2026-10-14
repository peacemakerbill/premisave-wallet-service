package com.premisave.wallet.commercial.repository;

import com.premisave.wallet.commercial.entity.CommercialIdempotencyRecord;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface CommercialIdempotencyRecordRepository extends MongoRepository<CommercialIdempotencyRecord, String> {
    Optional<CommercialIdempotencyRecord> findByMerchantIdAndLivemodeAndKey(String merchantId, boolean livemode, String key);
}
