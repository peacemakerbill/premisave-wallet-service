package com.premisave.wallet.commercial.repository.billing;

import com.premisave.wallet.commercial.entity.billing.BillingCustomer;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface BillingCustomerRepository extends MongoRepository<BillingCustomer, String> {
    Optional<BillingCustomer> findByCustomerIdAndMerchantIdAndLivemode(String customerId, String merchantId, boolean livemode);

    Optional<BillingCustomer> findByReferenceKey(String referenceKey);
}
