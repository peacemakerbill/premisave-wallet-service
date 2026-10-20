package com.premisave.wallet.commercial.entity.link;

import com.premisave.wallet.commercial.enums.MethodType;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A reusable page a customer opens to pay. The link only holds the rules (amount,
 * methods, how many times it can be used); every attempt is an ordinary payment that
 * carries the link id in its relations. status holds what has been decided so far
 * (ACTIVE, DISABLED or COMPLETED); an ACTIVE link whose expiresAt has passed reads as EXPIRED.
 */
@Document(collection = "commercial_payment_links")
@CompoundIndexes({
    @CompoundIndex(name = "merchant_env_created", def = "{'merchantId': 1, 'livemode': 1, 'createdAt': -1}"),
    @CompoundIndex(name = "merchant_env_reference", def = "{'merchantId': 1, 'livemode': 1, 'merchantReference': 1}")
})
public class PaymentLink {

    public enum AmountType { FIXED, OPEN }

    public enum Usage { SINGLE, MULTIPLE }

    public enum Status { ACTIVE, DISABLED, COMPLETED }

    @Id
    private String id;
    private String merchantId;
    private String appId;
    private boolean livemode;
    private String merchantReference;
    private String description;
    private AmountType amountType = AmountType.FIXED;
    private BigDecimal amount;
    private BigDecimal minAmount;
    private BigDecimal maxAmount;
    private String currency;
    private Set<MethodType> allowedMethods = EnumSet.noneOf(MethodType.class);
    private Usage usage = Usage.MULTIPLE;
    /** Number of successful payments, refreshed whenever a payment through the link succeeds. */
    private long usageCount;
    private Status status = Status.ACTIVE;
    private String callbackUrl;
    private Instant expiresAt;
    private Map<String, String> metadata = new LinkedHashMap<>();
    private Instant createdAt;
    private Instant updatedAt;

    public PaymentLink() {}

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String v) { this.merchantId = v; }
    public String getAppId() { return appId; }
    public void setAppId(String v) { this.appId = v; }
    public boolean isLivemode() { return livemode; }
    public void setLivemode(boolean v) { this.livemode = v; }
    public String getMerchantReference() { return merchantReference; }
    public void setMerchantReference(String v) { this.merchantReference = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public AmountType getAmountType() { return amountType; }
    public void setAmountType(AmountType v) { this.amountType = v; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal v) { this.amount = v; }
    public BigDecimal getMinAmount() { return minAmount; }
    public void setMinAmount(BigDecimal v) { this.minAmount = v; }
    public BigDecimal getMaxAmount() { return maxAmount; }
    public void setMaxAmount(BigDecimal v) { this.maxAmount = v; }
    public String getCurrency() { return currency; }
    public void setCurrency(String v) { this.currency = v; }
    public Set<MethodType> getAllowedMethods() { return allowedMethods; }
    public void setAllowedMethods(Set<MethodType> v) { this.allowedMethods = v == null ? EnumSet.noneOf(MethodType.class) : v; }
    public Usage getUsage() { return usage; }
    public void setUsage(Usage v) { this.usage = v; }
    public long getUsageCount() { return usageCount; }
    public void setUsageCount(long v) { this.usageCount = v; }
    public Status getStatus() { return status; }
    public void setStatus(Status v) { this.status = v; }
    public String getCallbackUrl() { return callbackUrl; }
    public void setCallbackUrl(String v) { this.callbackUrl = v; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant v) { this.expiresAt = v; }
    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> v) { this.metadata = v == null ? new LinkedHashMap<>() : v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
