package com.premisave.wallet.commercial.service;

import com.premisave.wallet.commercial.entity.AppSnapshot;
import com.premisave.wallet.commercial.enums.AppStatus;
import com.premisave.wallet.commercial.enums.Environment;
import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.onboarding.Fake;
import com.premisave.wallet.commercial.repository.AppSnapshotRepository;
import com.premisave.wallet.commercial.security.AppContext;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppSnapshotServiceTest {

    private final Map<String, AppSnapshot> store = new HashMap<>();
    private final AppSnapshotService service = new AppSnapshotService(Fake.of(AppSnapshotRepository.class, Map.of(
            "findById", a -> Optional.ofNullable(store.get((String) a[0])),
            "save", a -> {
                AppSnapshot s = (AppSnapshot) a[0];
                store.put(s.getAppId(), s);
                return s;
            })));

    private static AppContext ctx(String merchant, AppStatus status, long version) {
        return new AppContext("app_1", merchant, Environment.LIVE, status, null, null, version);
    }

    @Test
    void theFirstSettingsAreStoredAndFound() {
        assertTrue(service.apply(ctx("mer_1", AppStatus.ACTIVE, 10)));
        AppContext c = service.find("app_1").orElseThrow();
        assertEquals(AppStatus.ACTIVE, c.status());
        assertEquals(10, c.version());
        assertEquals("mer_1", c.merchantId());
    }

    @Test
    void newerWinsAndOlderOrEqualIsIgnored() {
        service.apply(ctx("mer_1", AppStatus.ACTIVE, 10));
        assertTrue(service.apply(ctx("mer_1", AppStatus.SUSPENDED, 11)));
        assertFalse(service.apply(ctx("mer_1", AppStatus.ACTIVE, 10)));
        assertFalse(service.apply(ctx("mer_1", AppStatus.ACTIVE, 11)));
        assertEquals(AppStatus.SUSPENDED, service.find("app_1").orElseThrow().status());
    }

    @Test
    void anAppCannotMoveToAnotherMerchant() {
        service.apply(ctx("mer_1", AppStatus.ACTIVE, 10));
        CommercialException e = assertThrows(CommercialException.class, () -> service.apply(ctx("mer_2", AppStatus.ACTIVE, 99)));
        assertEquals(ErrorCode.INVALID_STATE, e.code());
        assertEquals("mer_1", service.find("app_1").orElseThrow().merchantId());
    }

    @Test
    void unknownAppsAreAbsent() {
        assertFalse(service.find("app_none").isPresent());
        assertFalse(service.find(null).isPresent());
    }

    @Test
    void recordQuietlyNeverThrows() {
        service.apply(ctx("mer_1", AppStatus.ACTIVE, 10));
        service.recordQuietly(ctx("mer_2", AppStatus.ACTIVE, 99));
        assertEquals("mer_1", service.find("app_1").orElseThrow().merchantId());
    }
}
