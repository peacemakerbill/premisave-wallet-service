package com.premisave.wallet.commercial.repository;

import com.premisave.wallet.commercial.onboarding.OnboardingEvent;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface OnboardingEventRepository extends MongoRepository<OnboardingEvent, String> {
    List<OnboardingEvent> findByApplicationIdOrderByAtAsc(String applicationId);
}
