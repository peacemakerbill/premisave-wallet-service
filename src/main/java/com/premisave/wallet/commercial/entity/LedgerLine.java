package com.premisave.wallet.commercial.entity;

import com.premisave.wallet.commercial.enums.LedgerEntryType;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One line per balance change, written right after the atomic wallet update.
 * The unique (walletId, objectId, entryType) key means a retried
 * operation can never post the same line twice.
 */
@Document(collection = "commercial_ledger")
@CompoundIndexes({
    @CompoundIndex(name = "wallet_object_type_unique", def = "{'walletId': 1, 'objectId': 1, 'entryType': 1}", unique = true),
    @CompoundIndex(name = "wallet_created", def = "{'walletId': 1, 'createdAt': -1}")
})
public class LedgerLine {

    @Id
    private String id;

    private String walletId;
    private String merchantId;
    private boolean livemode;
    private LedgerEntryType entryType;
    private String objectId;
    private String description;

    /** Signed change to the available balance. */
    private long availableDeltaCents;
    /** Signed change to the held balance. */
    private long heldDeltaCents;
    /** Signed change to the pending balance. */
    private long pendingDeltaCents;

    /** Fee included in this line, for statement totals. */
    private long feeCents;

    private long availableAfterCents;
    private long heldAfterCents;
    private long pendingAfterCents;
    private Instant createdAt;

    public LedgerLine() {}

    public LedgerLine(String id, MerchantWallet after, LedgerEntryType type, String objectId, String description,
                      long availableDelta, long heldDelta, long pendingDelta, long feeCents, Instant now) {
        this.id = id;
        this.walletId = after.getWalletId();
        this.merchantId = after.getMerchantId();
        this.livemode = after.isLivemode();
        this.entryType = type;
        this.objectId = objectId;
        this.description = description;
        this.availableDeltaCents = availableDelta;
        this.heldDeltaCents = heldDelta;
        this.pendingDeltaCents = pendingDelta;
        this.feeCents = feeCents;
        this.availableAfterCents = after.getAvailableCents();
        this.heldAfterCents = after.getHeldCents();
        this.pendingAfterCents = after.getPendingCents();
        this.createdAt = now;
    }

    public String getId() { return id; }
    public String getWalletId() { return walletId; }
    public String getMerchantId() { return merchantId; }
    public boolean isLivemode() { return livemode; }
    public LedgerEntryType getEntryType() { return entryType; }
    public String getObjectId() { return objectId; }
    public String getDescription() { return description; }
    public long getAvailableDeltaCents() { return availableDeltaCents; }
    public long getHeldDeltaCents() { return heldDeltaCents; }
    public long getPendingDeltaCents() { return pendingDeltaCents; }
    public long getFeeCents() { return feeCents; }
    public long getAvailableAfterCents() { return availableAfterCents; }
    public long getHeldAfterCents() { return heldAfterCents; }
    public long getPendingAfterCents() { return pendingAfterCents; }
    public Instant getCreatedAt() { return createdAt; }
}
