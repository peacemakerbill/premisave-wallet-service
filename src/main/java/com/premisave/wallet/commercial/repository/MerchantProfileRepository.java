package com.premisave.wallet.commercial.repository;

import com.premisave.wallet.commercial.entity.MerchantProfile;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface MerchantProfileRepository extends MongoRepository<MerchantProfile, String> {
    Optional<MerchantProfile> findByDeveloperAccountId(String developerAccountId);
    Optional<MerchantProfile> findByMerchantNumber(String merchantNumber);
}
