package com.premisave.wallet.commercial.link;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A reference a customer types as the account number when paying a shared paybill, for
 * example PS1234-INV001. The reference is the id: it is unique across every merchant and
 * environment, so an incoming payment can only ever belong to one merchant.
 */
@Document(collection = "commercial_account_references")
@CompoundIndexes({
    @CompoundIndex(name = "merchant_env_reference", def = "{'merchantId': 1, 'livemode': 1, 'merchantReference': 1}", unique = true)
})
public class AccountReference {

    @Id
    private String reference;
    private String merchantId;
    private String appId;
    private boolean livemode;
    private String merchantReference;
    private BigDecimal expectedAmount;
    private String currency;
    private String status = "OPEN";
    private String methodType;
    private String provider;
    private String shortcode;
    private Instant expiresAt;
    private Instant createdAt;
    private Instant updatedAt;

    public AccountReference() {}

    public String getReference() { return reference; }
    public void setReference(String v) { this.reference = v; }
    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String v) { this.merchantId = v; }
    public String getAppId() { return appId; }
    public void setAppId(String v) { this.appId = v; }
    public boolean isLivemode() { return livemode; }
    public void setLivemode(boolean v) { this.livemode = v; }
    public String getMerchantReference() { return merchantReference; }
    public void setMerchantReference(String v) { this.merchantReference = v; }
    public BigDecimal getExpectedAmount() { return expectedAmount; }
    public void setExpectedAmount(BigDecimal v) { this.expectedAmount = v; }
    public String getCurrency() { return currency; }
    public void setCurrency(String v) { this.currency = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getMethodType() { return methodType; }
    public void setMethodType(String v) { this.methodType = v; }
    public String getProvider() { return provider; }
    public void setProvider(String v) { this.provider = v; }
    public String getShortcode() { return shortcode; }
    public void setShortcode(String v) { this.shortcode = v; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant v) { this.expiresAt = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
