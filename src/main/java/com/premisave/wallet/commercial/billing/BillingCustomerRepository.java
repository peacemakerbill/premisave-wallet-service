package com.premisave.wallet.commercial.billing;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface BillingCustomerRepository extends MongoRepository<BillingCustomer, String> {
    Optional<BillingCustomer> findByCustomerIdAndMerchantIdAndLivemode(String customerId, String merchantId, boolean livemode);

    Optional<BillingCustomer> findByReferenceKey(String referenceKey);
}
