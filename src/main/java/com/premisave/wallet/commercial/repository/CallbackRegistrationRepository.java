package com.premisave.wallet.commercial.repository;

import com.premisave.wallet.commercial.entity.CallbackRegistration;
import com.premisave.wallet.commercial.enums.RegistrationStatus;
import com.premisave.wallet.commercial.enums.RegistrationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface CallbackRegistrationRepository extends MongoRepository<CallbackRegistration, String> {
    Optional<CallbackRegistration> findByIdAndMerchantIdAndLivemode(String id, String merchantId, boolean livemode);
    Optional<CallbackRegistration> findByAppIdAndLivemodeAndUrl(String appId, boolean livemode, String url);
    long countByAppIdAndLivemode(String appId, boolean livemode);
    List<CallbackRegistration> findByAppIdAndLivemodeAndTypeAndStatus(
            String appId, boolean livemode, RegistrationType type, RegistrationStatus status);
    Page<CallbackRegistration> findByMerchantIdAndLivemode(String merchantId, boolean livemode, Pageable pageable);
}
