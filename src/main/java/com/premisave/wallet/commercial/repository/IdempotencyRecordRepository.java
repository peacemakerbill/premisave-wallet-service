package com.premisave.wallet.commercial.repository;

import com.premisave.wallet.commercial.entity.IdempotencyRecord;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface IdempotencyRecordRepository extends MongoRepository<IdempotencyRecord, String> {
    Optional<IdempotencyRecord> findByMerchantIdAndLivemodeAndKey(String merchantId, boolean livemode, String key);
}
