package com.premisave.wallet.commercial.onboarding;

import java.util.Optional;

/** Finds the Premisave wallet an application names, by the account number typed on the form. */
public interface WalletDirectory {

    /** A wallet as the application needs to know it. */
    record WalletRef(String walletId, String accountNumber, boolean frozen) {}

    Optional<WalletRef> find(String accountNumber);
}
