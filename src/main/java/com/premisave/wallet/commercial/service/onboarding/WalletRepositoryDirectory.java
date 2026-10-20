package com.premisave.wallet.commercial.service.onboarding;

import com.premisave.wallet.repository.WalletRepository;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/** The wallet collection behind WalletDirectory. */
@Component("commercialWalletDirectory")
public class WalletRepositoryDirectory implements WalletDirectory {

    private final WalletRepository wallets;

    public WalletRepositoryDirectory(WalletRepository wallets) {
        this.wallets = wallets;
    }

    @Override
    public Optional<WalletRef> find(String accountNumber) {
        if (accountNumber == null || accountNumber.isBlank()) return Optional.empty();
        String key = accountNumber.trim();
        return wallets.findByAccountNumber(key)
                .or(() -> wallets.findByAccountNumber(key.toLowerCase(Locale.ROOT)))
                .map(w -> new WalletRef(w.getId(), w.getAccountNumber(), w.isFrozen()));
    }
}
