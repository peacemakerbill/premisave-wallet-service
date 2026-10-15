package com.premisave.wallet.commercial.billing;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface CustomerPaymentMethodRepository extends MongoRepository<CustomerPaymentMethod, String> {
    Optional<CustomerPaymentMethod> findByPaymentMethodIdAndMerchantIdAndLivemode(String id, String merchantId, boolean livemode);

    List<CustomerPaymentMethod> findByCustomerId(String customerId);
}
