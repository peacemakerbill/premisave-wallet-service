package com.premisave.wallet.commercial.repository.dispute;

import com.premisave.wallet.commercial.entity.dispute.Dispute;
import com.premisave.wallet.commercial.enums.RailId;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface DisputeRepository extends MongoRepository<Dispute, String> {
    Optional<Dispute> findByIdAndMerchantIdAndLivemode(String id, String merchantId, boolean livemode);

    Optional<Dispute> findByRailAndProviderDisputeId(RailId rail, String providerDisputeId);
}
