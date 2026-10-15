package com.premisave.wallet.commercial.beneficiary;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One beneficiary verification. A repeat for the same destination within the cache window reuses the
 * earlier provider answer (cached true) but is still its own check, because the expected name may differ.
 * matchName is the full name used for comparing and is never returned to merchants. Checks are
 * kept for 30 days so a payout can point at the check it relied on.
 */
@Document(collection = "commercial_beneficiary_checks")
@CompoundIndexes({
    @CompoundIndex(name = "cache_lookup", def = "{'merchantId': 1, 'livemode': 1, 'destinationKey': 1, 'createdAt': -1}")
})
public class BeneficiaryCheck {

    @Id
    private String checkId;
    private String merchantId;
    private String appId;
    private boolean livemode;
    private String direction;
    private String destinationType;
    private String destinationKey;
    private Map<String, Object> destination = new LinkedHashMap<>();
    /** True when the provider (or format check) gave an answer, including "no such account". False when it could not be asked. */
    private boolean resolved;
    private boolean verified;
    private String registeredName;
    private String matchName;
    private String expectedName;
    private String match;
    private String source;
    private boolean cached;
    @Indexed(expireAfter = "30d")
    private Instant createdAt;
    private Instant expiresAt;

    public BeneficiaryCheck() {}

    public String getCheckId() { return checkId; }
    public void setCheckId(String v) { this.checkId = v; }
    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String v) { this.merchantId = v; }
    public String getAppId() { return appId; }
    public void setAppId(String v) { this.appId = v; }
    public boolean isLivemode() { return livemode; }
    public void setLivemode(boolean v) { this.livemode = v; }
    public String getDirection() { return direction; }
    public void setDirection(String v) { this.direction = v; }
    public String getDestinationType() { return destinationType; }
    public void setDestinationType(String v) { this.destinationType = v; }
    public String getDestinationKey() { return destinationKey; }
    public void setDestinationKey(String v) { this.destinationKey = v; }
    public Map<String, Object> getDestination() { return destination; }
    public void setDestination(Map<String, Object> v) { this.destination = v; }
    public boolean isResolved() { return resolved; }
    public void setResolved(boolean v) { this.resolved = v; }
    public boolean isVerified() { return verified; }
    public void setVerified(boolean v) { this.verified = v; }
    public String getRegisteredName() { return registeredName; }
    public void setRegisteredName(String v) { this.registeredName = v; }
    public String getMatchName() { return matchName; }
    public void setMatchName(String v) { this.matchName = v; }
    public String getExpectedName() { return expectedName; }
    public void setExpectedName(String v) { this.expectedName = v; }
    public String getMatch() { return match; }
    public void setMatch(String v) { this.match = v; }
    public String getSource() { return source; }
    public void setSource(String v) { this.source = v; }
    public boolean isCached() { return cached; }
    public void setCached(boolean v) { this.cached = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant v) { this.expiresAt = v; }
}
