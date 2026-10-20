package com.premisave.wallet.commercial.service;

import com.premisave.wallet.commercial.entity.AppSnapshot;
import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.repository.AppSnapshotRepository;
import com.premisave.wallet.commercial.security.AppContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Keeps the newest app settings the developer service has sent, for background work. An older version never replaces
 * a newer one, and an app can never move to a different merchant.
 */
@Service("commercialAppSnapshotService")
public class AppSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(AppSnapshotService.class);

    private final AppSnapshotRepository snapshots;

    public AppSnapshotService(AppSnapshotRepository snapshots) {
        this.snapshots = snapshots;
    }

    /** Stores the settings when they are newer than what is held. Returns true when they were stored. */
    public boolean apply(AppContext incoming) {
        AppSnapshot existing = snapshots.findById(incoming.appId()).orElse(null);
        if (existing == null) {
            snapshots.save(AppSnapshot.of(incoming));
            return true;
        }
        if (!existing.getMerchantId().equals(incoming.merchantId())) {
            throw new CommercialException(ErrorCode.INVALID_STATE, "This app belongs to a different merchant");
        }
        if (existing.getVersion() >= incoming.version()) return false;
        existing.apply(incoming);
        snapshots.save(existing);
        return true;
    }

    /** Called from the request path, where the request itself is the authority, so a failure here only costs the copy. */
    public void recordQuietly(AppContext incoming) {
        try {
            apply(incoming);
        } catch (RuntimeException e) {
            log.warn("Could not keep a copy of app {}: {}", incoming.appId(), e.getMessage());
        }
    }

    public Optional<AppContext> find(String appId) {
        if (appId == null) return Optional.empty();
        return snapshots.findById(appId).map(AppSnapshot::toContext);
    }
}
