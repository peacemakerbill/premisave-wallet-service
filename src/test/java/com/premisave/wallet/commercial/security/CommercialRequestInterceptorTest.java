package com.premisave.wallet.commercial.security;

import com.premisave.wallet.commercial.config.CommercialProperties;
import com.premisave.wallet.commercial.entity.AppSnapshot;
import com.premisave.wallet.commercial.entity.MerchantProfile;
import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.enums.Environment;
import com.premisave.wallet.commercial.enums.MerchantStatus;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.onboarding.Fake;
import com.premisave.wallet.commercial.repository.AppSnapshotRepository;
import com.premisave.wallet.commercial.repository.MerchantProfileRepository;
import com.premisave.wallet.commercial.service.AppSnapshotService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The context a request gets: merchant from this service's records, app from the developer service's headers. */
class CommercialRequestInterceptorTest {

    private final Map<String, AppSnapshot> snapshotStore = new HashMap<>();
    private final MerchantProfile merchant = new MerchantProfile();
    private final CommercialRequestInterceptor interceptor;

    CommercialRequestInterceptorTest() {
        merchant.setMerchantId("mer_1");
        merchant.setStatusFor(Environment.SANDBOX, MerchantStatus.ACTIVE);
        merchant.setStatusFor(Environment.LIVE, MerchantStatus.ACTIVE);
        MerchantProfileRepository merchants = Fake.of(MerchantProfileRepository.class, Map.of(
                "findById", a -> "mer_1".equals(a[0]) ? Optional.of(merchant) : Optional.empty()));
        AppSnapshotRepository repo = Fake.of(AppSnapshotRepository.class, Map.of(
                "findById", a -> Optional.ofNullable(snapshotStore.get((String) a[0])),
                "save", a -> {
                    AppSnapshot s = (AppSnapshot) a[0];
                    snapshotStore.put(s.getAppId(), s);
                    return s;
                }));
        interceptor = new CommercialRequestInterceptor(
                new CommercialProperties(null, null, null, null, null, null, null, null, null), merchants, new AppSnapshotService(repo));
    }

    private static Map<String, String> headers(String env) {
        Map<String, String> h = new HashMap<>();
        h.put("X-App-Environment", env);
        h.put("X-Merchant-Id", "mer_1");
        h.put("X-App-Id", "app_1");
        h.put("X-App-Status", "ACTIVE");
        h.put("X-App-Version", "100");
        return h;
    }

    private static HttpServletRequest request(String method, String uri, Map<String, String> headers) {
        return Fake.of(HttpServletRequest.class, Map.of(
                "getMethod", a -> method,
                "getRequestURI", a -> uri,
                "getHeader", a -> headers.get((String) a[0]),
                "getAttribute", a -> null));
    }

    private boolean run(String method, String uri, Map<String, String> headers) {
        return interceptor.preHandle(request(method, uri, headers), null, null);
    }

    private static CommercialException fails(Runnable r) {
        return assertThrows(CommercialException.class, r::run);
    }

    @Test
    void anActiveAppGetsAContextBuiltFromTheHeaders() {
        assertTrue(run("POST", "/internal/commercial/v1/sandbox/payments", headers("sandbox")));
        CommercialContext ctx = CommercialContextHolder.require();
        assertEquals("app_1", ctx.appId());
        assertEquals("mer_1", ctx.merchantId());
        assertEquals(Environment.SANDBOX, ctx.environment());
        assertTrue(ctx.app().active());
        CommercialContextHolder.clear();
    }

    @Test
    void theAppIsRememberedForBackgroundWork() {
        run("GET", "/internal/commercial/v1/live/balance", headers("production"));
        assertEquals(100, snapshotStore.get("app_1").getVersion());
        CommercialContextHolder.clear();
    }

    @Test
    void aSuspendedAppCanReadButNotChangeAnything() {
        Map<String, String> h = headers("production");
        h.put("X-App-Status", "SUSPENDED");
        assertTrue(run("GET", "/internal/commercial/v1/live/balance", h));
        CommercialContextHolder.clear();
        assertEquals(ErrorCode.APP_SUSPENDED, fails(() -> run("POST", "/internal/commercial/v1/live/payments", h)).code());
    }

    @Test
    void theEnvironmentHeaderMustMatchThePath() {
        assertEquals(ErrorCode.ENVIRONMENT_MISMATCH, fails(() -> run("GET", "/internal/commercial/v1/live/balance", headers("sandbox"))).code());
        assertEquals(ErrorCode.ENVIRONMENT_MISMATCH, fails(() -> run("GET", "/internal/commercial/v1/sandbox/balance", headers("production"))).code());
        Map<String, String> none = headers("sandbox");
        none.remove("X-App-Environment");
        assertEquals(ErrorCode.ENVIRONMENT_MISMATCH, fails(() -> run("GET", "/internal/commercial/v1/sandbox/balance", none)).code());
    }

    @Test
    void anUnknownMerchantIsRefused() {
        Map<String, String> h = headers("sandbox");
        h.put("X-Merchant-Id", "mer_nope");
        assertEquals(ErrorCode.MERCHANT_NOT_FOUND, fails(() -> run("GET", "/internal/commercial/v1/sandbox/balance", h)).code());
    }

    @Test
    void missingAppHeadersAreRefused() {
        Map<String, String> h = headers("sandbox");
        h.remove("X-App-Status");
        assertEquals(ErrorCode.VALIDATION_FAILED, fails(() -> run("GET", "/internal/commercial/v1/sandbox/balance", h)).code());
    }

    @Test
    void liveWaitsForApprovalAndStateChangesNeedAnActiveMerchant() {
        merchant.setStatusFor(Environment.LIVE, MerchantStatus.PENDING_APPROVAL);
        assertEquals(ErrorCode.LIVE_NOT_ENABLED, fails(() -> run("GET", "/internal/commercial/v1/live/balance", headers("production"))).code());
        merchant.setStatusFor(Environment.LIVE, MerchantStatus.SUSPENDED);
        assertTrue(run("GET", "/internal/commercial/v1/live/balance", headers("production")));
        CommercialContextHolder.clear();
        assertEquals(ErrorCode.MERCHANT_SUSPENDED, fails(() -> run("POST", "/internal/commercial/v1/live/payments", headers("production"))).code());
    }

    @Test
    void aFailingSnapshotStoreDoesNotFailTheRequest() {
        MerchantProfileRepository merchants = Fake.of(MerchantProfileRepository.class, Map.of("findById", a -> Optional.of(merchant)));
        AppSnapshotRepository broken = Fake.of(AppSnapshotRepository.class, Map.of(
                "findById", a -> { throw new IllegalStateException("db down"); }));
        CommercialRequestInterceptor i = new CommercialRequestInterceptor(
                new CommercialProperties(null, null, null, null, null, null, null, null, null), merchants, new AppSnapshotService(broken));
        assertTrue(i.preHandle(request("GET", "/internal/commercial/v1/sandbox/balance", headers("sandbox")), null, null));
        assertFalse(snapshotStore.containsKey("app_1"));
        CommercialContextHolder.clear();
    }
}
