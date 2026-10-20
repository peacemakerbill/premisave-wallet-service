package com.premisave.wallet.commercial.repository.transfer;

import com.premisave.wallet.commercial.entity.transfer.WalletCreditMarker;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface WalletCreditMarkerRepository extends MongoRepository<WalletCreditMarker, String> {
}
