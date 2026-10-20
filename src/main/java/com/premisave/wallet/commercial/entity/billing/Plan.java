package com.premisave.wallet.commercial.entity.billing;

import com.premisave.wallet.commercial.enums.MethodType;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a customer is charged and how often (section 2.10). planId is chosen by the merchant and unique
 * per merchant and environment. Price, currency and interval never change; a subscription copies the
 * terms when it starts, so later edits to a plan do not touch it.
 */
@Document(collection = "commercial_plans")
@CompoundIndexes({
    @CompoundIndex(name = "merchant_env_plan", def = "{'merchantId': 1, 'livemode': 1, 'planId': 1}", unique = true),
    @CompoundIndex(name = "merchant_env_status_created", def = "{'merchantId': 1, 'livemode': 1, 'status': 1, 'createdAt': -1}")
})
public class Plan {

    public enum Interval { DAY, WEEK, MONTH, YEAR }

    public enum Status { ACTIVE, ARCHIVED }

    public enum OnExhausted { CANCEL, EXPIRE }

    /** The ways a customer can be billed again and again. Bank transfers and crypto have no stored credential. */
    public static final Set<MethodType> RECURRING_METHODS =
            EnumSet.of(MethodType.CARD, MethodType.PAYPAL, MethodType.PREMISAVE_WALLET, MethodType.MOBILE_MONEY);

    @Id
    private String id;

    private String planId;
    private String merchantId;
    private boolean livemode;
    private String name;
    private BigDecimal amount;
    private String currency;
    private Interval interval;
    private int intervalCount = 1;
    private int trialDays;
    private BigDecimal setupFee = BigDecimal.ZERO;
    private Set<MethodType> allowedMethods = EnumSet.noneOf(MethodType.class);
    private List<Integer> retryScheduleDays = new ArrayList<>();
    private OnExhausted onExhausted = OnExhausted.CANCEL;
    private Status status = Status.ACTIVE;
    private Map<String, String> metadata = new LinkedHashMap<>();
    private Instant createdAt;
    private Instant updatedAt;

    public Plan() {}

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getPlanId() { return planId; }
    public void setPlanId(String v) { this.planId = v; }
    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String v) { this.merchantId = v; }
    public boolean isLivemode() { return livemode; }
    public void setLivemode(boolean v) { this.livemode = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal v) { this.amount = v; }
    public String getCurrency() { return currency; }
    public void setCurrency(String v) { this.currency = v; }
    public Interval getInterval() { return interval; }
    public void setInterval(Interval v) { this.interval = v; }
    public int getIntervalCount() { return intervalCount; }
    public void setIntervalCount(int v) { this.intervalCount = v; }
    public int getTrialDays() { return trialDays; }
    public void setTrialDays(int v) { this.trialDays = v; }
    public BigDecimal getSetupFee() { return setupFee; }
    public void setSetupFee(BigDecimal v) { this.setupFee = v; }
    public Set<MethodType> getAllowedMethods() { return allowedMethods; }
    public void setAllowedMethods(Set<MethodType> v) { this.allowedMethods = v; }
    public List<Integer> getRetryScheduleDays() { return retryScheduleDays; }
    public void setRetryScheduleDays(List<Integer> v) { this.retryScheduleDays = v; }
    public OnExhausted getOnExhausted() { return onExhausted; }
    public void setOnExhausted(OnExhausted v) { this.onExhausted = v; }
    public Status getStatus() { return status; }
    public void setStatus(Status v) { this.status = v; }
    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> v) { this.metadata = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
