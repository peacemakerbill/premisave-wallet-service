package com.premisave.wallet.commercial.repository.beneficiary;

import com.premisave.wallet.commercial.entity.beneficiary.BeneficiaryCheck;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface BeneficiaryCheckRepository extends MongoRepository<BeneficiaryCheck, String> {
    Optional<BeneficiaryCheck> findByCheckIdAndMerchantIdAndLivemode(String checkId, String merchantId, boolean livemode);
}
