package com.premisave.wallet.commercial.entity;

import com.premisave.wallet.commercial.enums.AppStatus;
import com.premisave.wallet.commercial.enums.Environment;
import com.premisave.wallet.commercial.enums.MethodType;
import com.premisave.wallet.commercial.security.AppContext;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Set;

/**
 * The last settings the developer service sent for an app. Requests carry the settings themselves, so this copy is
 * only for work that has no request behind it (scheduled settlements, subscription billing). It is never an
 * authority for a live request.
 */
@Document(collection = "commercial_app_snapshots")
public class AppSnapshot {

    @Id
    private String appId;

    @Indexed
    private String merchantId;
    private Environment environment;
    private AppStatus status;
    private Set<MethodType> enabledMethods;
    private Limits limits;
    private long version;
    private Instant updatedAt;

    public AppSnapshot() {}

    public static AppSnapshot of(AppContext c) {
        AppSnapshot s = new AppSnapshot();
        s.appId = c.appId();
        s.apply(c);
        return s;
    }

    public void apply(AppContext c) {
        this.merchantId = c.merchantId();
        this.environment = c.environment();
        this.status = c.status();
        this.enabledMethods = c.enabledMethods();
        this.limits = c.limits();
        this.version = c.version();
        this.updatedAt = Instant.now();
    }

    public AppContext toContext() {
        return new AppContext(appId, merchantId, environment, status, enabledMethods, limits, version);
    }

    public String getAppId() { return appId; }
    public void setAppId(String v) { this.appId = v; }
    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String v) { this.merchantId = v; }
    public Environment getEnvironment() { return environment; }
    public void setEnvironment(Environment v) { this.environment = v; }
    public AppStatus getStatus() { return status; }
    public void setStatus(AppStatus v) { this.status = v; }
    public Set<MethodType> getEnabledMethods() { return enabledMethods; }
    public void setEnabledMethods(Set<MethodType> v) { this.enabledMethods = v; }
    public Limits getLimits() { return limits; }
    public void setLimits(Limits v) { this.limits = v; }
    public long getVersion() { return version; }
    public void setVersion(long v) { this.version = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
