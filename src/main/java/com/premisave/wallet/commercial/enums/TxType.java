package com.premisave.wallet.commercial.enums;

public enum TxType {
    PAYMENT, PAYOUT, REFUND, SUBSCRIPTION_CHARGE, SETTLEMENT, TRANSFER, ADJUSTMENT, FEE;

    /** Money leaves the merchant balance: shown as a negative net in the ledger view. */
    public boolean isDebit() {
        return this == PAYOUT || this == SETTLEMENT || this == TRANSFER || this == REFUND || this == FEE;
    }

    /**
     * Amount plus fee is held first, then captured on success or released on failure.
     * This is the model payouts introduced; settlements and transfers share it.
     */
    public boolean usesHold() {
        return this == PAYOUT || this == SETTLEMENT || this == TRANSFER;
    }

    /** Human wording for ledger descriptions and messages. */
    public String label() {
        return switch (this) {
            case PAYMENT -> "Payment";
            case PAYOUT -> "Payout";
            case REFUND -> "Refund";
            case SUBSCRIPTION_CHARGE -> "Subscription charge";
            case SETTLEMENT -> "Settlement";
            case TRANSFER -> "Transfer";
            case ADJUSTMENT -> "Adjustment";
            case FEE -> "Fee";
        };
    }
}
