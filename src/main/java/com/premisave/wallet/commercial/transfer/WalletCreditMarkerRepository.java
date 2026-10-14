package com.premisave.wallet.commercial.transfer;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface WalletCreditMarkerRepository extends MongoRepository<WalletCreditMarker, String> {
}
