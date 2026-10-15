package com.premisave.wallet.commercial.link;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface AccountReferenceRepository extends MongoRepository<AccountReference, String> {
    Optional<AccountReference> findByReferenceAndMerchantIdAndLivemode(String reference, String merchantId, boolean livemode);
}
