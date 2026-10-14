package com.premisave.wallet.commercial.entity;

import com.premisave.wallet.commercial.enums.Environment;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A merchant's USD ledger for one environment. Deliberately separate from the
 * consumer Wallet collection so merchant balances never appear in consumer
 * reports, and so money can be kept in whole cents and moved with atomic
 * increments.
 *
 * available: spendable now. held: reserved for in-flight payouts and
 * disputes. pending: received but not yet released by the provider.
 */
@Document(collection = "merchant_wallets")
@CompoundIndex(name = "merchant_env_unique", def = "{'merchantId': 1, 'livemode': 1}", unique = true)
public class MerchantWallet {

    @Id
    private String walletId;

    private String merchantId;
    private boolean livemode;
    private long availableCents;
    private long heldCents;
    private long pendingCents;

    /**
     * Keys of the most recent balance operations. An operation names its key, and the
     * update only applies when the key is absent, which makes every operation
     * exactly-once without needing multi-document transactions.
     */
    private List<String> appliedOps = new ArrayList<>();

    private Instant createdAt;
    private Instant updatedAt;

    public MerchantWallet() {}

    public MerchantWallet(String walletId, String merchantId, Environment env, Instant now) {
        this.walletId = walletId;
        this.merchantId = merchantId;
        this.livemode = env.livemode();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getWalletId() { return walletId; }
    public String getMerchantId() { return merchantId; }
    public boolean isLivemode() { return livemode; }
    public long getAvailableCents() { return availableCents; }
    public long getHeldCents() { return heldCents; }
    public long getPendingCents() { return pendingCents; }
    public List<String> getAppliedOps() { return appliedOps; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public long totalCents() { return availableCents + heldCents + pendingCents; }
}
