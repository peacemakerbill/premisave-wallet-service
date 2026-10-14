package com.premisave.wallet.commercial.repository;

import com.premisave.wallet.commercial.entity.MerchantWallet;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface MerchantWalletRepository extends MongoRepository<MerchantWallet, String> {
    Optional<MerchantWallet> findByMerchantIdAndLivemode(String merchantId, boolean livemode);
}
