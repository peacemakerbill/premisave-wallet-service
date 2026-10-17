package com.premisave.wallet.commercial.controller;

import com.premisave.wallet.commercial.dto.AppContextRequest;
import com.premisave.wallet.commercial.dto.LimitsDto;
import com.premisave.wallet.commercial.entity.Limits;
import com.premisave.wallet.commercial.enums.Environment;
import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.repository.MerchantProfileRepository;
import com.premisave.wallet.commercial.security.AppContext;
import com.premisave.wallet.commercial.service.AppSnapshotService;
import com.premisave.wallet.commercial.service.MerchantService;
import com.premisave.wallet.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;

/** The developer service tells this service about app changes (new, suspended, new limits). */
@RestController("commercialAppContextController")
@RequestMapping("/internal/commercial/v1/apps")
public class CommercialAppContextController {

    private final AppSnapshotService snapshots;
    private final MerchantProfileRepository merchants;

    public CommercialAppContextController(AppSnapshotService snapshots, MerchantProfileRepository merchants) {
        this.snapshots = snapshots;
        this.merchants = merchants;
    }

    @PutMapping("/{appId}/context")
    public ResponseEntity<ApiResponse<Map<String, Object>>> put(@PathVariable String appId,
                                                                @Valid @RequestBody AppContextRequest req) {
        if (!merchants.existsById(req.merchantId())) {
            throw new CommercialException(ErrorCode.MERCHANT_NOT_FOUND, "Merchant not found");
        }
        Environment env = MerchantService.parseEnvironment("environment", req.environment());
        AppContext ctx = new AppContext(appId, req.merchantId(), env, req.status(),
                req.enabledMethods() == null ? null
                        : req.enabledMethods().isEmpty() ? EnumSet.noneOf(com.premisave.wallet.commercial.enums.MethodType.class)
                        : EnumSet.copyOf(req.enabledMethods()),
                limits(req.limits()), req.version());
        boolean applied = snapshots.apply(ctx);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("appId", appId);
        m.put("version", req.version());
        m.put("applied", applied);
        return Responses.ok(applied ? "App settings stored" : "A newer version is already stored", m);
    }

    private static Limits limits(LimitsDto dto) {
        if (dto == null) return null;
        Limits l = new Limits();
        l.setSingleTransactionUsd(dto.singleTransactionUsd());
        l.setDailyVolumeUsd(dto.dailyVolumeUsd());
        l.setMonthlyVolumeUsd(dto.monthlyVolumeUsd());
        l.setDailyPayoutCount(dto.dailyPayoutCount());
        return l.isEmpty() ? null : l;
    }
}
