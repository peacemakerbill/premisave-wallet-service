package com.premisave.wallet.commercial.onboarding;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** One step in the life of an application. Append only, never edited. */
@Document(collection = "onboarding_events")
public class OnboardingEvent {

    @Id
    private String eventId;
    @Indexed
    private String applicationId;
    private String merchantId;
    private OnboardingEventType type;
    private ActorType actorType;
    private String actorId;
    private String actorName;
    private String actorRole;
    private OnboardingStatus fromStatus;
    private OnboardingStatus toStatus;
    private String message;
    /** Internal notes and staff only steps are false. */
    private boolean visibleToApplicant;
    @Indexed
    private Instant at;

    public OnboardingEvent() {}

    public String getEventId() { return eventId; }
    public void setEventId(String v) { this.eventId = v; }
    public String getApplicationId() { return applicationId; }
    public void setApplicationId(String v) { this.applicationId = v; }
    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String v) { this.merchantId = v; }
    public OnboardingEventType getType() { return type; }
    public void setType(OnboardingEventType v) { this.type = v; }
    public ActorType getActorType() { return actorType; }
    public void setActorType(ActorType v) { this.actorType = v; }
    public String getActorId() { return actorId; }
    public void setActorId(String v) { this.actorId = v; }
    public String getActorName() { return actorName; }
    public void setActorName(String v) { this.actorName = v; }
    public String getActorRole() { return actorRole; }
    public void setActorRole(String v) { this.actorRole = v; }
    public OnboardingStatus getFromStatus() { return fromStatus; }
    public void setFromStatus(OnboardingStatus v) { this.fromStatus = v; }
    public OnboardingStatus getToStatus() { return toStatus; }
    public void setToStatus(OnboardingStatus v) { this.toStatus = v; }
    public String getMessage() { return message; }
    public void setMessage(String v) { this.message = v; }
    public boolean isVisibleToApplicant() { return visibleToApplicant; }
    public void setVisibleToApplicant(boolean v) { this.visibleToApplicant = v; }
    public Instant getAt() { return at; }
    public void setAt(Instant v) { this.at = v; }
}
