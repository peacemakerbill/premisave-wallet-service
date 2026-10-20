package com.premisave.wallet.commercial.service.onboarding;

import com.premisave.wallet.commercial.entity.onboarding.Actor;
import com.premisave.wallet.commercial.entity.onboarding.OnboardingApplication;
import com.premisave.wallet.commercial.entity.onboarding.OnboardingEvent;
import com.premisave.wallet.commercial.enums.onboarding.OnboardingEventType;
import com.premisave.wallet.commercial.enums.onboarding.OnboardingStatus;
import com.premisave.wallet.commercial.repository.onboarding.OnboardingEventRepository;
import com.premisave.wallet.commercial.util.Ids;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/** Writes the append only record of every step of an application. */
@Component("commercialOnboardingTimeline")
public class OnboardingTimeline {

    private final OnboardingEventRepository events;

    public OnboardingTimeline(OnboardingEventRepository events) {
        this.events = events;
    }

    public OnboardingEvent record(OnboardingApplication app, OnboardingEventType type, Actor actor,
                                  OnboardingStatus from, OnboardingStatus to, String message, boolean visibleToApplicant) {
        OnboardingEvent e = new OnboardingEvent();
        e.setEventId(Ids.next("oev"));
        e.setApplicationId(app.getApplicationId());
        e.setMerchantId(app.getMerchantId());
        e.setType(type);
        e.setActorType(actor.type());
        e.setActorId(actor.id());
        e.setActorName(actor.name());
        e.setActorRole(actor.role());
        e.setFromStatus(from);
        e.setToStatus(to);
        e.setMessage(message);
        e.setVisibleToApplicant(visibleToApplicant);
        e.setAt(Instant.now());
        return events.insert(e);
    }

    public List<OnboardingEvent> of(String applicationId, boolean applicantView) {
        List<OnboardingEvent> all = events.findByApplicationIdOrderByAtAsc(applicationId);
        return applicantView ? all.stream().filter(OnboardingEvent::isVisibleToApplicant).toList() : all;
    }
}
