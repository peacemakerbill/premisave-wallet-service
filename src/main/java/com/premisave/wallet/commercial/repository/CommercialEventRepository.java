package com.premisave.wallet.commercial.repository;

import com.premisave.wallet.commercial.entity.CommercialEvent;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface CommercialEventRepository extends MongoRepository<CommercialEvent, String> {
    Optional<CommercialEvent> findByEventIdAndMerchantIdAndLivemode(String eventId, String merchantId, boolean livemode);
}
