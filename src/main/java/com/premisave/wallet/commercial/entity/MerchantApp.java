package com.premisave.wallet.commercial.entity;

import com.premisave.wallet.commercial.enums.AppStatus;
import com.premisave.wallet.commercial.enums.Environment;
import com.premisave.wallet.commercial.enums.MethodType;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Set;

/**
 * An app registered by the developer service. Holds identity, status and
 * optional narrowing of the merchant's methods and limits. No credentials
 * of any kind are ever stored here.
 */
@Document(collection = "merchant_apps")
public class MerchantApp {

    @Id
    private String appId;

    @Indexed
    private String merchantId;

    private Environment environment;
    private String name;
    private AppStatus status = AppStatus.ACTIVE;

    /** Null means "same as the merchant". */
    private Set<MethodType> enabledMethods;
    /** Null means "same as the merchant". */
    private Limits limits;

    private Instant createdAt;
    private Instant updatedAt;

    public MerchantApp() {}

    public String getAppId() { return appId; }
    public void setAppId(String v) { this.appId = v; }
    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String v) { this.merchantId = v; }
    public Environment getEnvironment() { return environment; }
    public void setEnvironment(Environment v) { this.environment = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public AppStatus getStatus() { return status; }
    public void setStatus(AppStatus v) { this.status = v; }
    public Set<MethodType> getEnabledMethods() { return enabledMethods; }
    public void setEnabledMethods(Set<MethodType> v) { this.enabledMethods = v; }
    public Limits getLimits() { return limits; }
    public void setLimits(Limits v) { this.limits = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
