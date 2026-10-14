package com.premisave.wallet.commercial.transfer;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** Proof that a commercial transaction has already credited a Premisave wallet. The id is the transaction id. */
@Document(collection = "commercial_wallet_credits")
public class WalletCreditMarker {

    @Id
    private String transactionId;
    private String walletId;
    private long cents;
    private Instant at;

    public WalletCreditMarker() {}

    public WalletCreditMarker(String transactionId, String walletId, long cents, Instant at) {
        this.transactionId = transactionId;
        this.walletId = walletId;
        this.cents = cents;
        this.at = at;
    }

    public String getTransactionId() { return transactionId; }
    public String getWalletId() { return walletId; }
    public long getCents() { return cents; }
    public Instant getAt() { return at; }
}
