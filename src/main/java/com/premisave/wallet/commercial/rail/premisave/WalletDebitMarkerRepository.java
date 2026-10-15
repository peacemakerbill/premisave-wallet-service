package com.premisave.wallet.commercial.rail.premisave;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface WalletDebitMarkerRepository extends MongoRepository<WalletDebitMarker, String> {
}
