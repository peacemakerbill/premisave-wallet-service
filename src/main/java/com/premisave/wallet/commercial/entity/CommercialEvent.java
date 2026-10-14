package com.premisave.wallet.commercial.entity;

import com.premisave.wallet.commercial.enums.ForwardStatus;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An event and its outbox state in one document. The event is written in the
 * same step as the change it describes, then forwarded to the developer
 * service by EventDispatcher, which retries until it is accepted.
 */
@Document(collection = "event_outbox")
@CompoundIndexes({
    @CompoundIndex(name = "merchant_env_created", def = "{'merchantId': 1, 'livemode': 1, 'createdAt': -1}"),
    @CompoundIndex(name = "forward_due", def = "{'forwardStatus': 1, 'nextAttemptAt': 1}")
})
public class CommercialEvent {

    @Id
    private String eventId;

    private String type;
    private String merchantId;
    private String appId;
    private boolean livemode;
    private String objectId;
    private Map<String, Object> data = new LinkedHashMap<>();
    private List<EventTarget> targets = new ArrayList<>();
    private ForwardStatus forwardStatus = ForwardStatus.PENDING;
    private int attempts;
    private Instant nextAttemptAt;
    private Instant lockedUntil;
    private String lastError;
    private Instant createdAt;
    private Instant deliveredAt;

    public CommercialEvent() {}

    public String getEventId() { return eventId; }
    public void setEventId(String v) { this.eventId = v; }
    public String getType() { return type; }
    public void setType(String v) { this.type = v; }
    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String v) { this.merchantId = v; }
    public String getAppId() { return appId; }
    public void setAppId(String v) { this.appId = v; }
    public boolean isLivemode() { return livemode; }
    public void setLivemode(boolean v) { this.livemode = v; }
    public String getObjectId() { return objectId; }
    public void setObjectId(String v) { this.objectId = v; }
    public Map<String, Object> getData() { return data; }
    public void setData(Map<String, Object> v) { this.data = v; }
    public List<EventTarget> getTargets() { return targets; }
    public void setTargets(List<EventTarget> v) { this.targets = v; }
    public ForwardStatus getForwardStatus() { return forwardStatus; }
    public void setForwardStatus(ForwardStatus v) { this.forwardStatus = v; }
    public int getAttempts() { return attempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant v) { this.nextAttemptAt = v; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getDeliveredAt() { return deliveredAt; }
}
