package com.premisave.wallet.commercial.entity;

import com.premisave.wallet.commercial.enums.Direction;
import com.premisave.wallet.commercial.enums.MethodType;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;

/** A priced preview. Honoured by a payment or payout that names its id, until it expires. */
@Document(collection = "commercial_quotes")
public class Quote {

    @Id
    private String quoteId;

    private String merchantId;
    private boolean livemode;
    private Direction direction;
    private MethodType methodType;
    private String rail;
    private String provider;
    private String country;
    private BigDecimal amount;
    private String currency;
    private long usdCents;
    private BigDecimal nativeAmount;
    private String nativeCurrency;
    private BigDecimal fxRate;
    private long feeCents;
    private long netCents;
    private Instant createdAt;

    @Indexed(expireAfter = "0s")
    private Instant expiresAt;

    public Quote() {}

    public String getQuoteId() { return quoteId; }
    public void setQuoteId(String v) { this.quoteId = v; }
    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String v) { this.merchantId = v; }
    public boolean isLivemode() { return livemode; }
    public void setLivemode(boolean v) { this.livemode = v; }
    public Direction getDirection() { return direction; }
    public void setDirection(Direction v) { this.direction = v; }
    public MethodType getMethodType() { return methodType; }
    public void setMethodType(MethodType v) { this.methodType = v; }
    public String getRail() { return rail; }
    public void setRail(String v) { this.rail = v; }
    public String getProvider() { return provider; }
    public void setProvider(String v) { this.provider = v; }
    public String getCountry() { return country; }
    public void setCountry(String v) { this.country = v; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal v) { this.amount = v; }
    public String getCurrency() { return currency; }
    public void setCurrency(String v) { this.currency = v; }
    public long getUsdCents() { return usdCents; }
    public void setUsdCents(long v) { this.usdCents = v; }
    public BigDecimal getNativeAmount() { return nativeAmount; }
    public void setNativeAmount(BigDecimal v) { this.nativeAmount = v; }
    public String getNativeCurrency() { return nativeCurrency; }
    public void setNativeCurrency(String v) { this.nativeCurrency = v; }
    public BigDecimal getFxRate() { return fxRate; }
    public void setFxRate(BigDecimal v) { this.fxRate = v; }
    public long getFeeCents() { return feeCents; }
    public void setFeeCents(long v) { this.feeCents = v; }
    public long getNetCents() { return netCents; }
    public void setNetCents(long v) { this.netCents = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant v) { this.expiresAt = v; }
}
