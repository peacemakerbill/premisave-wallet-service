package com.premisave.wallet.commercial.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** Money reserved against a merchant wallet. Moves ACTIVE to CAPTURED or RELEASED exactly once. */
@Document(collection = "merchant_holds")
public class MerchantHold {

    public static final String ACTIVE = "ACTIVE";
    public static final String CAPTURED = "CAPTURED";
    public static final String RELEASED = "RELEASED";

    @Id
    private String holdId;

    private String walletId;
    private String merchantId;
    private boolean livemode;

    @Indexed(unique = true)
    private String transactionId;

    private long amountCents;
    private String reason;
    private String state = ACTIVE;
    private Instant createdAt;
    private Instant closedAt;

    public MerchantHold() {}

    public MerchantHold(String holdId, MerchantWallet wallet, String transactionId, long amountCents,
                        String reason, Instant now) {
        this.holdId = holdId;
        this.walletId = wallet.getWalletId();
        this.merchantId = wallet.getMerchantId();
        this.livemode = wallet.isLivemode();
        this.transactionId = transactionId;
        this.amountCents = amountCents;
        this.reason = reason;
        this.createdAt = now;
    }

    public String getHoldId() { return holdId; }
    public String getWalletId() { return walletId; }
    public String getMerchantId() { return merchantId; }
    public boolean isLivemode() { return livemode; }
    public String getTransactionId() { return transactionId; }
    public long getAmountCents() { return amountCents; }
    public String getReason() { return reason; }
    public String getState() { return state; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getClosedAt() { return closedAt; }
}
