package com.premisave.wallet.commercial.repository;

import com.premisave.wallet.commercial.onboarding.OnboardingApplication;
import com.premisave.wallet.commercial.onboarding.OnboardingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface OnboardingApplicationRepository extends MongoRepository<OnboardingApplication, String> {
    Optional<OnboardingApplication> findByMerchantId(String merchantId);
    Optional<OnboardingApplication> findByMerchantNumber(String merchantNumber);
    Page<OnboardingApplication> findByStatus(OnboardingStatus status, Pageable pageable);
}
