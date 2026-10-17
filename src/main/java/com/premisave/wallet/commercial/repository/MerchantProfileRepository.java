package com.premisave.wallet.commercial.repository;

import com.premisave.wallet.commercial.entity.MerchantProfile;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface MerchantProfileRepository extends MongoRepository<MerchantProfile, String> {
    List<MerchantProfile> findByDeveloperAccountIdOrderByCreatedAtAsc(String developerAccountId);
    Optional<MerchantProfile> findByMerchantNumber(String merchantNumber);
}
