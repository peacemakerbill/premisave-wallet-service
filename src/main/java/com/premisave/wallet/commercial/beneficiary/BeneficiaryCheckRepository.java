package com.premisave.wallet.commercial.beneficiary;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface BeneficiaryCheckRepository extends MongoRepository<BeneficiaryCheck, String> {
    Optional<BeneficiaryCheck> findByCheckIdAndMerchantIdAndLivemode(String checkId, String merchantId, boolean livemode);
}
