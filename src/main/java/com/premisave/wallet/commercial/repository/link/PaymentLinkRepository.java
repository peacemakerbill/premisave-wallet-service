package com.premisave.wallet.commercial.repository.link;

import com.premisave.wallet.commercial.entity.link.PaymentLink;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface PaymentLinkRepository extends MongoRepository<PaymentLink, String> {
    Optional<PaymentLink> findByIdAndMerchantIdAndLivemode(String id, String merchantId, boolean livemode);
}
