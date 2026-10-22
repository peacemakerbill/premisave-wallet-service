package com.premisave.wallet.enums;

/**
 * Lifecycle of one payout out of an escrow hold.
 *
 *  PENDING  being paid, the outcome is not known yet (settled by a recovery sweep if it stays pending).
 *  SUCCESS  the recipient has been credited.
 *  FAILED   refused or interrupted before the recipient was credited; the amount went back to the hold.
 *           The reference may be used again.
 */
public enum EscrowPayoutStatus {
    PENDING,
    SUCCESS,
    FAILED
}
