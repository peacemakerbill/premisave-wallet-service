package com.premisave.wallet.service;

import java.math.BigDecimal;

/**
 * Changes a user's wallet balance for escrow, exactly once per movement key. MongoEscrowWalletMovements is the
 * real implementation.
 *
 * A movement key names one intended balance change (for example "&lt;holdId&gt;-D1" for the debit of attempt 1 of a
 * hold). Whatever happens, a key is applied at most once, and once cancel() has ruled it out it is never applied.
 * That is what lets a recovery sweep settle a request that died half way without ever racing the request itself.
 */
public interface EscrowWalletMovements {

    /**
     * Takes amount out of the wallet. True when this call took it, false when the same key had already been applied.
     * Throws WalletNotFoundException, WalletFrozenException, InsufficientFundsException when refused, and
     * IllegalStateException when the key was cancelled.
     */
    boolean debit(String walletId, String key, BigDecimal amount, String reference, String description);

    /** Puts amount into the wallet. Same contract as debit, minus the funds check. */
    boolean credit(String walletId, String key, BigDecimal amount, String reference, String description);

    /**
     * Rules the key out for good. True when the movement is guaranteed never to be applied (this call cancelled it,
     * or it had been cancelled before); false when it had already been applied, in which case nothing changes.
     */
    boolean cancel(String walletId, String key);

    /** Writes the row that shows the movement in the wallet's statement if it is missing. Signed: negative is a debit. */
    void ensureHistory(String walletId, BigDecimal signedAmount, String reference, String description);
}
