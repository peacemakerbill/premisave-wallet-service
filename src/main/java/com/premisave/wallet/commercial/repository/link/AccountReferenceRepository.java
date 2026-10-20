package com.premisave.wallet.commercial.repository.link;

import com.premisave.wallet.commercial.entity.link.AccountReference;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface AccountReferenceRepository extends MongoRepository<AccountReference, String> {
    Optional<AccountReference> findByReferenceAndMerchantIdAndLivemode(String reference, String merchantId, boolean livemode);
}
