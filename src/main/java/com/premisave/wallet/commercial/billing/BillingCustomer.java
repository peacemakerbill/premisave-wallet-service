package com.premisave.wallet.commercial.billing;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One of a merchant's own customers, the party that is billed (section 2.11). Not a Premisave user.
 * referenceKey (merchant, environment and merchantReference joined) carries the uniqueness of
 * merchantReference; it is empty when the customer has none, and the index is sparse so those
 * customers do not collide.
 */
@Document(collection = "commercial_customers")
@CompoundIndexes({
    @CompoundIndex(name = "merchant_env_created", def = "{'merchantId': 1, 'livemode': 1, 'createdAt': -1}"),
    @CompoundIndex(name = "merchant_env_email", def = "{'merchantId': 1, 'livemode': 1, 'email': 1}", sparse = true),
    @CompoundIndex(name = "merchant_env_phone", def = "{'merchantId': 1, 'livemode': 1, 'phone': 1}", sparse = true)
})
public class BillingCustomer {

    @Id
    private String customerId;

    private String merchantId;
    private boolean livemode;
    private String name;
    private String email;
    private String phone;
    private String merchantReference;

    @Indexed(unique = true, sparse = true)
    private String referenceKey;

    private Map<String, String> metadata = new LinkedHashMap<>();
    private Instant createdAt;
    private Instant updatedAt;

    public BillingCustomer() {}

    public static String referenceKeyFor(String merchantId, boolean livemode, String merchantReference) {
        return merchantReference == null ? null : merchantId + "|" + (livemode ? "live" : "sandbox") + "|" + merchantReference;
    }

    public String getCustomerId() { return customerId; }
    public void setCustomerId(String v) { this.customerId = v; }
    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String v) { this.merchantId = v; }
    public boolean isLivemode() { return livemode; }
    public void setLivemode(boolean v) { this.livemode = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getEmail() { return email; }
    public void setEmail(String v) { this.email = v; }
    public String getPhone() { return phone; }
    public void setPhone(String v) { this.phone = v; }
    public String getMerchantReference() { return merchantReference; }
    public void setMerchantReference(String v) {
        this.merchantReference = v;
        this.referenceKey = referenceKeyFor(merchantId, livemode, v);
    }
    public String getReferenceKey() { return referenceKey; }
    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> v) { this.metadata = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
