package com.premisave.wallet.commercial.entity.billing;

import com.premisave.wallet.commercial.enums.MethodType;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A way to bill a customer repeatedly (section 2.12). Only the provider's token is kept, never card
 * numbers. Mobile money has no stored credential: the phone number is kept and every cycle sends a
 * new prompt to it.
 */
@Document(collection = "customer_payment_methods")
@CompoundIndexes({
    @CompoundIndex(name = "customer_status", def = "{'customerId': 1, 'status': 1}")
})
public class CustomerPaymentMethod {

    public enum Status { PENDING_AUTHORISATION, ACTIVE, FAILED, REMOVED }

    @Id
    private String paymentMethodId;

    private String customerId;
    private String merchantId;
    /** The app that added it, so the event about it goes to that app's callbacks. */
    private String appId;
    private boolean livemode;
    private MethodType type;
    private String provider;
    private String label;
    private Status status = Status.PENDING_AUTHORISATION;
    private boolean defaultMethod;
    private Map<String, Object> nextAction;
    /** phoneNumber for mobile money, accountRef for a Premisave wallet. */
    private Map<String, String> details = new LinkedHashMap<>();
    private String returnUrl;
    /** What the provider gave us to charge with later. Never shown to the merchant. */
    private String providerToken;
    private String failureReason;
    private Instant createdAt;
    private Instant updatedAt;

    public CustomerPaymentMethod() {}

    public String getPaymentMethodId() { return paymentMethodId; }
    public void setPaymentMethodId(String v) { this.paymentMethodId = v; }
    public String getCustomerId() { return customerId; }
    public void setCustomerId(String v) { this.customerId = v; }
    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String v) { this.merchantId = v; }
    public String getAppId() { return appId; }
    public void setAppId(String v) { this.appId = v; }
    public boolean isLivemode() { return livemode; }
    public void setLivemode(boolean v) { this.livemode = v; }
    public MethodType getType() { return type; }
    public void setType(MethodType v) { this.type = v; }
    public String getProvider() { return provider; }
    public void setProvider(String v) { this.provider = v; }
    public String getLabel() { return label; }
    public void setLabel(String v) { this.label = v; }
    public Status getStatus() { return status; }
    public void setStatus(Status v) { this.status = v; }
    public boolean isDefaultMethod() { return defaultMethod; }
    public void setDefaultMethod(boolean v) { this.defaultMethod = v; }
    public Map<String, Object> getNextAction() { return nextAction; }
    public void setNextAction(Map<String, Object> v) { this.nextAction = v; }
    public Map<String, String> getDetails() { return details; }
    public void setDetails(Map<String, String> v) { this.details = v; }
    public String getReturnUrl() { return returnUrl; }
    public void setReturnUrl(String v) { this.returnUrl = v; }
    public String getProviderToken() { return providerToken; }
    public void setProviderToken(String v) { this.providerToken = v; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String v) { this.failureReason = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
