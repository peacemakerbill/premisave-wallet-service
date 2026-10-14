package com.premisave.wallet.commercial.repository;

import com.premisave.wallet.commercial.entity.MerchantApp;
import com.premisave.wallet.commercial.enums.AppStatus;
import com.premisave.wallet.commercial.enums.Environment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface MerchantAppRepository extends MongoRepository<MerchantApp, String> {
    Page<MerchantApp> findByMerchantId(String merchantId, Pageable pageable);
    Page<MerchantApp> findByMerchantIdAndEnvironment(String merchantId, Environment environment, Pageable pageable);
    Page<MerchantApp> findByMerchantIdAndStatus(String merchantId, AppStatus status, Pageable pageable);
    Page<MerchantApp> findByMerchantIdAndEnvironmentAndStatus(String merchantId, Environment environment,
                                                              AppStatus status, Pageable pageable);
}
