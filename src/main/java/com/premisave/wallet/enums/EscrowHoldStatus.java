package com.premisave.wallet.enums;

/**
 * Lifecycle of an escrow hold (see EscrowService).
 *
 *  PENDING        being created, the outcome is not known yet. Never stays pending: a recovery sweep settles a
 *                 stale one to HELD (the sender was already debited) or FAILED (nothing moved).
 *  HELD           the sender has been debited and the full amount is held.
 *  PARTIALLY_PAID some of it has been paid out, some is still held.
 *  PAID_OUT       nothing is left in the hold.
 *  FAILED         the hold was refused or interrupted before any money moved. The reference may be used again.
 */
public enum EscrowHoldStatus {
    PENDING,
    HELD,
    PARTIALLY_PAID,
    PAID_OUT,
    FAILED
}
