package com.premisave.wallet.commercial.rail.premisave;

import com.premisave.wallet.commercial.service.billing.PaymentMethodService;
import com.premisave.wallet.commercial.util.Money;
import com.premisave.wallet.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The wallet owner's side of Premisave wallet payments: the merchant requests waiting for an answer and the
 * standing approvals already given. Used by the Premisave app, so it sits under /wallet like the other
 * consumer endpoints and is authenticated as the wallet owner (the account is the token's email). It is
 * deliberately not part of the merchant facing commercial API.
 */
@RestController
@RequestMapping("/wallet/consents")
@ConditionalOnProperty(prefix = "commercial.rails.premisave", name = "enabled", havingValue = "true")
public class WalletConsentController {

    private static final Logger log = LoggerFactory.getLogger(WalletConsentController.class);

    private final WalletConsentService consents;
    private final PaymentMethodService methods;

    public WalletConsentController(WalletConsentService consents, PaymentMethodService methods) {
        this.consents = consents;
        this.methods = methods;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(Authentication auth) {
        List<Map<String, Object>> rows = consents.openFor(auth.getName()).stream().map(WalletConsentController::view).toList();
        return ResponseEntity.ok(ApiResponse.success("Requests retrieved", rows));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<ApiResponse<Map<String, Object>>> approve(Authentication auth, @PathVariable String id) {
        WalletConsentService.Decision d = consents.approve(auth.getName(), id);
        if (d.ok() && d.consent() != null && d.consent().getKind() == WalletConsent.Kind.MANDATE
                && d.consent().getPaymentMethodId() != null) {
            try {
                methods.activate(d.consent().getPaymentMethodId(), "Premisave wallet", d.consent().getId());
            } catch (RuntimeException e) {
                // The pending method poller completes it a moment later.
                log.warn("Payment method {} could not be activated yet: {}", d.consent().getPaymentMethodId(), e.getMessage());
            }
        }
        return respond(d);
    }

    @PostMapping("/{id}/decline")
    public ResponseEntity<ApiResponse<Map<String, Object>>> decline(Authentication auth, @PathVariable String id) {
        return respond(consents.decline(auth.getName(), id));
    }

    @PostMapping("/{id}/revoke")
    public ResponseEntity<ApiResponse<Map<String, Object>>> revoke(Authentication auth, @PathVariable String id) {
        return respond(consents.revoke(auth.getName(), id));
    }

    private static ResponseEntity<ApiResponse<Map<String, Object>>> respond(WalletConsentService.Decision d) {
        Map<String, Object> body = d.consent() == null ? null : view(d.consent());
        if (d.ok()) return ResponseEntity.ok(ApiResponse.success(d.message(), body));
        return ResponseEntity.status(d.httpStatus()).body(ApiResponse.error(d.message(), body));
    }

    /** What the owner sees. Nothing about the merchant's account or the payment beyond what is needed to decide. */
    static Map<String, Object> view(WalletConsent c) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", c.getId());
        v.put("kind", c.getKind().name());
        v.put("status", c.getStatus().name());
        v.put("merchant", c.getMerchantName());
        v.put(c.getKind() == WalletConsent.Kind.MANDATE ? "maxPerChargeUsd" : "amountUsd", Money.fromCents(c.getAmountCents()).toPlainString());
        v.put("description", c.getDescription());
        v.put("createdAt", c.getCreatedAt() == null ? null : c.getCreatedAt().toString());
        v.put("expiresAt", c.getKind() == WalletConsent.Kind.MANDATE && c.getStatus() == WalletConsent.Status.APPROVED ? null
                : c.getExpiresAt() == null ? null : c.getExpiresAt().toString());
        return v;
    }
}
