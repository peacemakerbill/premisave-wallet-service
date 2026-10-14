package com.premisave.wallet.commercial.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** Remembers an Idempotency-Key for 24 hours. The unique key makes concurrent first requests race safely. */
@Document(collection = "idempotency_records")
@CompoundIndex(name = "merchant_env_key_unique", def = "{'merchantId': 1, 'livemode': 1, 'key': 1}", unique = true)
public class IdempotencyRecord {

    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String COMPLETED = "COMPLETED";

    @Id
    private String id;

    private String merchantId;
    private boolean livemode;
    private String key;
    private String requestHash;
    private String state = IN_PROGRESS;
    private String resourceType;
    private String resourceId;

    @Indexed(expireAfter = "0s")
    private Instant expiresAt;

    private Instant createdAt;

    public IdempotencyRecord() {}

    public IdempotencyRecord(String id, String merchantId, boolean livemode, String key, String requestHash,
                             Instant now, Instant expiresAt) {
        this.id = id;
        this.merchantId = merchantId;
        this.livemode = livemode;
        this.key = key;
        this.requestHash = requestHash;
        this.createdAt = now;
        this.expiresAt = expiresAt;
    }

    public String getId() { return id; }
    public String getMerchantId() { return merchantId; }
    public boolean isLivemode() { return livemode; }
    public String getKey() { return key; }
    public String getRequestHash() { return requestHash; }
    public String getState() { return state; }
    public void setState(String v) { this.state = v; }
    public String getResourceType() { return resourceType; }
    public void setResourceType(String v) { this.resourceType = v; }
    public String getResourceId() { return resourceId; }
    public void setResourceId(String v) { this.resourceId = v; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
}