package com.premisave.wallet.commercial.repository.onboarding;

import com.premisave.wallet.commercial.entity.onboarding.OnboardingApplication;
import com.premisave.wallet.commercial.enums.onboarding.OnboardingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface OnboardingApplicationRepository extends MongoRepository<OnboardingApplication, String> {
    Optional<OnboardingApplication> findByMerchantId(String merchantId);
    Optional<OnboardingApplication> findByMerchantNumber(String merchantNumber);
    List<OnboardingApplication> findByLinkedWalletAccountOrderByCreatedAtDesc(String linkedWalletAccount);
    Page<OnboardingApplication> findByStatus(OnboardingStatus status, Pageable pageable);
}
