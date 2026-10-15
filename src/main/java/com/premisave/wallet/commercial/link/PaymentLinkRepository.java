package com.premisave.wallet.commercial.link;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface PaymentLinkRepository extends MongoRepository<PaymentLink, String> {
    Optional<PaymentLink> findByIdAndMerchantIdAndLivemode(String id, String merchantId, boolean livemode);
}
