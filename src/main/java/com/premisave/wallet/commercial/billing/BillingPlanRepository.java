package com.premisave.wallet.commercial.billing;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface BillingPlanRepository extends MongoRepository<Plan, String> {
    Optional<Plan> findByPlanIdAndMerchantIdAndLivemode(String planId, String merchantId, boolean livemode);
}
