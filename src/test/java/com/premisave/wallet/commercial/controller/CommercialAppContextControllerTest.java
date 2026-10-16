package com.premisave.wallet.commercial.controller;

import com.premisave.wallet.commercial.dto.AppContextRequest;
import com.premisave.wallet.commercial.dto.LimitsDto;
import com.premisave.wallet.commercial.entity.AppSnapshot;
import com.premisave.wallet.commercial.enums.AppStatus;
import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.enums.MethodType;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.onboarding.Fake;
import com.premisave.wallet.commercial.repository.AppSnapshotRepository;
import com.premisave.wallet.commercial.repository.MerchantProfileRepository;
import com.premisave.wallet.commercial.service.AppSnapshotService;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.repository.WalletRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommercialAppContextControllerTest {

    private final Map<String, AppSnapshot> store = new HashMap<>();
    private final CommercialAppContextController controller = new CommercialAppContextController(
            new AppSnapshotService(Fake.of(AppSnapshotRepository.class, Map.of(
                    "findById", a -> Optional.ofNullable(store.get((String) a[0])),
                    "save", a -> {
                        AppSnapshot s = (AppSnapshot) a[0];
                        store.put(s.getAppId(), s);
                        return s;
                    }))),
            Fake.of(MerchantProfileRepository.class, Map.of("existsById", a -> "mer_1".equals(a[0]))));

    @Test
    void storesSettingsAndAnswersWhetherTheyWereApplied() {
        AppContextRequest req = new AppContextRequest("mer_1", "production", AppStatus.SUSPENDED, Set.of(MethodType.CARD),
                new LimitsDto(new BigDecimal("50"), null, null, 3), 200L);
        assertEquals(true, controller.put("app_1", req).getBody().getData().get("applied"));
        AppSnapshot s = store.get("app_1");
        assertEquals(AppStatus.SUSPENDED, s.getStatus());
        assertEquals(Set.of(MethodType.CARD), s.getEnabledMethods());
        assertEquals(new BigDecimal("50"), s.getLimits().getSingleTransactionUsd());
        assertEquals(3, s.getLimits().getDailyPayoutCount());

        AppContextRequest older = new AppContextRequest("mer_1", "production", AppStatus.ACTIVE, null, null, 100L);
        assertEquals(false, controller.put("app_1", older).getBody().getData().get("applied"));
        assertEquals(AppStatus.SUSPENDED, store.get("app_1").getStatus());
    }

    @Test
    void emptyLimitsAndMissingMethodsMeanNoNarrowing() {
        controller.put("app_2", new AppContextRequest("mer_1", "sandbox", AppStatus.ACTIVE, null, new LimitsDto(null, null, null, null), 5L));
        assertNull(store.get("app_2").getEnabledMethods());
        assertNull(store.get("app_2").getLimits());
        controller.put("app_3", new AppContextRequest("mer_1", "sandbox", AppStatus.ACTIVE, Set.of(), null, 5L));
        assertTrue(store.get("app_3").getEnabledMethods().isEmpty());
    }

    @Test
    void anUnknownMerchantOrEnvironmentIsRefused() {
        assertEquals(ErrorCode.MERCHANT_NOT_FOUND, assertThrows(CommercialException.class,
                () -> controller.put("app_1", new AppContextRequest("mer_x", "sandbox", AppStatus.ACTIVE, null, null, 1L))).code());
        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(CommercialException.class,
                () -> controller.put("app_1", new AppContextRequest("mer_1", "mars", AppStatus.ACTIVE, null, null, 1L))).code());
        assertTrue(store.isEmpty());
    }

    @Test
    void walletLookupFindsByAccountOrIdAndReportsFrozen() {
        Wallet w = new Wallet();
        w.setId("w1");
        w.setAccountNumber("owner@acme.test");
        Wallet frozen = new Wallet();
        frozen.setId("w2");
        frozen.setAccountNumber("f@acme.test");
        frozen.setFrozen(true);
        Map<String, Wallet> byId = Map.of("w1", w, "w2", frozen);
        Map<String, Wallet> byAccount = Map.of("owner@acme.test", w, "f@acme.test", frozen);
        CommercialWalletLookupController lookup = new CommercialWalletLookupController(Fake.of(WalletRepository.class, Map.of(
                "findByAccountNumber", a -> Optional.ofNullable(byAccount.get((String) a[0])),
                "findById", a -> Optional.ofNullable(byId.get((String) a[0])))));

        Map<String, Object> m = lookup.resolve(" Owner@Acme.test ").getBody().getData();
        assertEquals("w1", m.get("walletId"));
        assertEquals("owner@acme.test", m.get("accountNumber"));
        assertEquals(false, m.get("frozen"));
        assertEquals("w1", lookup.resolve("w1").getBody().getData().get("walletId"));
        assertEquals(true, lookup.resolve("f@acme.test").getBody().getData().get("frozen"));
        assertEquals(ErrorCode.WALLET_NOT_FOUND, assertThrows(CommercialException.class, () -> lookup.resolve("nobody")).code());
        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(CommercialException.class, () -> lookup.resolve("  ")).code());
        assertFalse(m.containsKey("balance"));
    }
}
