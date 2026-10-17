package com.premisave.wallet.commercial.controller;

import com.premisave.wallet.commercial.onboarding.OwnerApplicationService;
import com.premisave.wallet.dto.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * For the owner of a Premisave wallet that a merchant application names. Used by the Premisave app, so it sits under /wallet
 * like the other consumer endpoints and is authenticated as the wallet owner (the account is the token's email). Read only.
 */
@RestController("commercialWalletMerchantApplicationController")
@RequestMapping("/wallet")
public class WalletMerchantApplicationController {

    private final OwnerApplicationService owner;

    public WalletMerchantApplicationController(OwnerApplicationService owner) {
        this.owner = owner;
    }

    /** The applications that name this wallet, with how far each has got. */
    @GetMapping("/merchant-applications")
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success("Applications retrieved", owner.list(auth.getName())));
    }

    /** One application in full: details, documents, what was asked to be fixed, the decision and the timeline. */
    @GetMapping("/merchant-applications/{applicationId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(Authentication auth, @PathVariable String applicationId) {
        return ResponseEntity.ok(ApiResponse.success("Application retrieved", owner.get(auth.getName(), applicationId)));
    }

    /** The merchant (PRM) numbers linked to this wallet, once their applications are approved. */
    @GetMapping("/merchant-numbers")
    public ResponseEntity<ApiResponse<Map<String, Object>>> merchantNumbers(Authentication auth) {
        return ResponseEntity.ok(ApiResponse.success("Merchant numbers retrieved", owner.merchantNumbers(auth.getName())));
    }
}
