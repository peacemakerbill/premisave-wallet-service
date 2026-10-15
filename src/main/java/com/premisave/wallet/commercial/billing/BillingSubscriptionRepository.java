package com.premisave.wallet.commercial.billing;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface BillingSubscriptionRepository extends MongoRepository<Subscription, String> {
    Optional<Subscription> findByIdAndMerchantIdAndLivemode(String id, String merchantId, boolean livemode);

    Optional<Subscription> findByActiveKey(String activeKey);
}
