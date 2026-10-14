package com.premisave.wallet.commercial.entity;

import com.premisave.wallet.commercial.enums.MethodType;
import com.premisave.wallet.commercial.enums.RailId;
import com.premisave.wallet.commercial.enums.TxStatus;
import com.premisave.wallet.commercial.enums.TxType;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every payment, payout and (later) refund, settlement and transfer a
 * merchant makes. Amounts are USD cents; the merchant's own currency and the
 * rate used are kept alongside. State changes go through
 * CommercialTransactionStore so each one is applied exactly once.
 */
@Document(collection = "commercial_transactions")
@CompoundIndexes({
    @CompoundIndex(name = "merchant_env_type_created", def = "{'merchantId': 1, 'livemode': 1, 'type': 1, 'createdAt': -1}"),
    @CompoundIndex(name = "merchant_env_reference", def = "{'merchantId': 1, 'livemode': 1, 'merchantReference': 1}"),
    @CompoundIndex(name = "provider_reference", def = "{'rail': 1, 'providerReference': 1}", sparse = true)
})
public class CommercialTransaction {

    @Id
    private String id;

    private String merchantId;
    private String appId;
    private boolean livemode;
    private TxType type;
    private TxStatus status;
    private String merchantReference;
    private String description;
    private String remarks;

    /** The amount in USD cents. For payouts this is what the recipient is sent, before fee. */
    private long amountCents;
    private long feeCents;
    /** Collections: amount less fee, credited to the merchant. Payouts: amount plus fee, held then debited. */
    private long settleCents;

    private String currency;
    private BigDecimal nativeAmount;
    private String nativeCurrency;
    /** Units of native currency per 1 USD. */
    private BigDecimal fxRate;
    private String quoteId;

    private MethodType methodType;
    private RailId rail;
    private String provider;
    private String providerReference;

    private Map<String, Object> customer = new LinkedHashMap<>();
    private Map<String, Object> destination = new LinkedHashMap<>();
    private Map<String, Object> nextAction;
    private TxResult result;

    private String callbackUrl;
    private boolean notifyRegistered;
    private Map<String, String> metadata = new LinkedHashMap<>();
    private String holdId;

    /** Set once the money side of a final status has been applied to the wallet. */
    private boolean ledgerApplied;

    private long version = 1;
    private List<TimelineEntry> timeline = new ArrayList<>();
    private Instant createdAt;
    private Instant updatedAt;
    private Instant expiresAt;
    private Instant completedAt;

    public CommercialTransaction() {}

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String v) { this.merchantId = v; }
    public String getAppId() { return appId; }
    public void setAppId(String v) { this.appId = v; }
    public boolean isLivemode() { return livemode; }
    public void setLivemode(boolean v) { this.livemode = v; }
    public TxType getType() { return type; }
    public void setType(TxType v) { this.type = v; }
    public TxStatus getStatus() { return status; }
    public void setStatus(TxStatus v) { this.status = v; }
    public String getMerchantReference() { return merchantReference; }
    public void setMerchantReference(String v) { this.merchantReference = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public String getRemarks() { return remarks; }
    public void setRemarks(String v) { this.remarks = v; }
    public long getAmountCents() { return amountCents; }
    public void setAmountCents(long v) { this.amountCents = v; }
    public long getFeeCents() { return feeCents; }
    public void setFeeCents(long v) { this.feeCents = v; }
    public long getSettleCents() { return settleCents; }
    public void setSettleCents(long v) { this.settleCents = v; }
    public String getCurrency() { return currency; }
    public void setCurrency(String v) { this.currency = v; }
    public BigDecimal getNativeAmount() { return nativeAmount; }
    public void setNativeAmount(BigDecimal v) { this.nativeAmount = v; }
    public String getNativeCurrency() { return nativeCurrency; }
    public void setNativeCurrency(String v) { this.nativeCurrency = v; }
    public BigDecimal getFxRate() { return fxRate; }
    public void setFxRate(BigDecimal v) { this.fxRate = v; }
    public String getQuoteId() { return quoteId; }
    public void setQuoteId(String v) { this.quoteId = v; }
    public MethodType getMethodType() { return methodType; }
    public void setMethodType(MethodType v) { this.methodType = v; }
    public RailId getRail() { return rail; }
    public void setRail(RailId v) { this.rail = v; }
    public String getProvider() { return provider; }
    public void setProvider(String v) { this.provider = v; }
    public String getProviderReference() { return providerReference; }
    public void setProviderReference(String v) { this.providerReference = v; }
    public Map<String, Object> getCustomer() { return customer; }
    public void setCustomer(Map<String, Object> v) { this.customer = v; }
    public Map<String, Object> getDestination() { return destination; }
    public void setDestination(Map<String, Object> v) { this.destination = v; }
    public Map<String, Object> getNextAction() { return nextAction; }
    public void setNextAction(Map<String, Object> v) { this.nextAction = v; }
    public TxResult getResult() { return result; }
    public void setResult(TxResult v) { this.result = v; }
    public String getCallbackUrl() { return callbackUrl; }
    public void setCallbackUrl(String v) { this.callbackUrl = v; }
    public boolean isNotifyRegistered() { return notifyRegistered; }
    public void setNotifyRegistered(boolean v) { this.notifyRegistered = v; }
    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> v) { this.metadata = v; }
    public String getHoldId() { return holdId; }
    public void setHoldId(String v) { this.holdId = v; }
    public boolean isLedgerApplied() { return ledgerApplied; }
    public long getVersion() { return version; }
    public List<TimelineEntry> getTimeline() { return timeline; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant v) { this.expiresAt = v; }
    public Instant getCompletedAt() { return completedAt; }
}
