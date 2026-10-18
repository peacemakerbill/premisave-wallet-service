package com.premisave.wallet.commercial.entity;

import com.premisave.wallet.commercial.enums.Environment;
import com.premisave.wallet.commercial.enums.MerchantStatus;
import com.premisave.wallet.commercial.enums.MethodType;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * A business using the commercial API. One profile covers both environments; each has its own status and wallet. A
 * developer account can own many of these, one for each business it applies for. The compound index stops the same
 * business being added twice to one account.
 */
@Document(collection = "merchant_profiles")
@CompoundIndex(name = "developer_business_key", def = "{'developerAccountId': 1, 'applicationKey': 1}", unique = true, sparse = true)
public class MerchantProfile {

    @Id
    private String merchantId;

    @Indexed
    private String developerAccountId;

    /** See BusinessKeys. Unset only for merchants created before many businesses per account were allowed. */
    private String applicationKey;

    /** PRM- followed by ten digits, counted up from 1. Unset until the onboarding application is approved. */
    @Indexed(unique = true, sparse = true)
    private String merchantNumber;

    private String businessName;
    private String displayName;
    private String country;
    private String contactEmail;
    private String contactPhone;
    private String defaultCurrency = "USD";

    private MerchantStatus sandboxStatus = MerchantStatus.ACTIVE;
    private MerchantStatus liveStatus = MerchantStatus.PENDING_APPROVAL;
    private String sandboxWalletId;
    private String liveWalletId;

    private Set<MethodType> enabledMethods = EnumSet.noneOf(MethodType.class);
    private Limits liveLimits = new Limits();
    private Limits sandboxLimits = new Limits();
    private String feePlanId = "fee_standard";
    /** When true, payouts must pass a beneficiary check unless the request says otherwise. */
    private boolean requireBeneficiaryCheck;
    private List<SettlementDestination> liveSettlementDestinations = new ArrayList<>();
    private List<SettlementDestination> sandboxSettlementDestinations = new ArrayList<>();

    private Instant createdAt;
    private Instant updatedAt;

    public MerchantProfile() {}

    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String v) { this.merchantId = v; }
    public String getDeveloperAccountId() { return developerAccountId; }
    public void setDeveloperAccountId(String v) { this.developerAccountId = v; }
    public String getApplicationKey() { return applicationKey; }
    public void setApplicationKey(String v) { this.applicationKey = v; }
    public String getMerchantNumber() { return merchantNumber; }
    public void setMerchantNumber(String v) { this.merchantNumber = v; }
    public String getBusinessName() { return businessName; }
    public void setBusinessName(String v) { this.businessName = v; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String v) { this.displayName = v; }
    public String getCountry() { return country; }
    public void setCountry(String v) { this.country = v; }
    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String v) { this.contactEmail = v; }
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String v) { this.contactPhone = v; }
    public String getDefaultCurrency() { return defaultCurrency; }
    public void setDefaultCurrency(String v) { this.defaultCurrency = v; }
    public String getSandboxWalletId() { return sandboxWalletId; }
    public void setSandboxWalletId(String v) { this.sandboxWalletId = v; }
    public String getLiveWalletId() { return liveWalletId; }
    public void setLiveWalletId(String v) { this.liveWalletId = v; }
    public Set<MethodType> getEnabledMethods() { return enabledMethods; }
    public void setEnabledMethods(Set<MethodType> v) { this.enabledMethods = v; }
    public String getFeePlanId() { return feePlanId; }
    public void setFeePlanId(String v) { this.feePlanId = v; }
    public boolean isRequireBeneficiaryCheck() { return requireBeneficiaryCheck; }
    public void setRequireBeneficiaryCheck(boolean v) { this.requireBeneficiaryCheck = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }

    public MerchantStatus statusFor(Environment env) {
        return env == Environment.LIVE ? liveStatus : sandboxStatus;
    }

    public void setStatusFor(Environment env, MerchantStatus status) {
        if (env == Environment.LIVE) liveStatus = status; else sandboxStatus = status;
    }

    public String walletIdFor(Environment env) {
        return env == Environment.LIVE ? liveWalletId : sandboxWalletId;
    }

    public Limits limitsFor(Environment env) {
        Limits l = env == Environment.LIVE ? liveLimits : sandboxLimits;
        return l == null ? new Limits() : l;
    }

    public void setLimitsFor(Environment env, Limits limits) {
        if (env == Environment.LIVE) liveLimits = limits; else sandboxLimits = limits;
    }

    public List<SettlementDestination> destinationsFor(Environment env) {
        List<SettlementDestination> d = env == Environment.LIVE ? liveSettlementDestinations : sandboxSettlementDestinations;
        return d == null ? new ArrayList<>() : d;
    }

    public void setDestinationsFor(Environment env, List<SettlementDestination> destinations) {
        if (env == Environment.LIVE) liveSettlementDestinations = destinations; else sandboxSettlementDestinations = destinations;
    }
}