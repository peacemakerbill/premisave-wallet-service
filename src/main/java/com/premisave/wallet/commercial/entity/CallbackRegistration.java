package com.premisave.wallet.commercial.entity;

import com.premisave.wallet.commercial.enums.RegistrationStatus;
import com.premisave.wallet.commercial.enums.RegistrationType;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** A callback URL a merchant app has registered for events or for synchronous validation. Holds no secrets. */
@Document(collection = "callback_registrations")
@CompoundIndex(name = "app_env_url_unique", def = "{'appId': 1, 'livemode': 1, 'url': 1}", unique = true)
public class CallbackRegistration {

    @Id
    private String id;

    private String merchantId;
    private String appId;
    private boolean livemode;
    private RegistrationType type;
    private String url;
    private List<String> eventTypes = new ArrayList<>();
    /** ACCEPT or REJECT. Validation registrations only. */
    private String onTimeout;
    private String description;
    private RegistrationStatus status;
    private String statusReason;
    private Instant verifiedAt;
    private Instant createdAt;
    private Instant updatedAt;

    public CallbackRegistration() {}

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String v) { this.merchantId = v; }
    public String getAppId() { return appId; }
    public void setAppId(String v) { this.appId = v; }
    public boolean isLivemode() { return livemode; }
    public void setLivemode(boolean v) { this.livemode = v; }
    public RegistrationType getType() { return type; }
    public void setType(RegistrationType v) { this.type = v; }
    public String getUrl() { return url; }
    public void setUrl(String v) { this.url = v; }
    public List<String> getEventTypes() { return eventTypes; }
    public void setEventTypes(List<String> v) { this.eventTypes = v; }
    public String getOnTimeout() { return onTimeout; }
    public void setOnTimeout(String v) { this.onTimeout = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public RegistrationStatus getStatus() { return status; }
    public void setStatus(RegistrationStatus v) { this.status = v; }
    public String getStatusReason() { return statusReason; }
    public void setStatusReason(String v) { this.statusReason = v; }
    public Instant getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(Instant v) { this.verifiedAt = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
