package com.premisave.wallet.commercial.controller;

import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.dto.ApiResponse;
import com.premisave.wallet.entity.Wallet;
import com.premisave.wallet.repository.WalletRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Lets the developer service find a Premisave wallet by account number or id when a production app is linked to one. */
@RestController("commercialWalletLookupController")
@RequestMapping("/internal/commercial/v1/wallets")
public class CommercialWalletLookupController {

    private final WalletRepository wallets;

    public CommercialWalletLookupController(WalletRepository wallets) {
        this.wallets = wallets;
    }

    @GetMapping("/resolve")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resolve(@RequestParam String reference) {
        String key = reference == null ? "" : reference.trim();
        if (key.isEmpty()) throw CommercialException.validation("reference", "is required");
        Wallet wallet = wallets.findByAccountNumber(key)
                .or(() -> wallets.findByAccountNumber(key.toLowerCase(Locale.ROOT)))
                .or(() -> wallets.findById(key))
                .orElseThrow(() -> new CommercialException(ErrorCode.WALLET_NOT_FOUND,
                        "No Premisave wallet matches that account number or id"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("walletId", wallet.getId());
        m.put("accountNumber", wallet.getAccountNumber());
        m.put("frozen", wallet.isFrozen());
        return Responses.ok("Wallet found", m);
    }
}
