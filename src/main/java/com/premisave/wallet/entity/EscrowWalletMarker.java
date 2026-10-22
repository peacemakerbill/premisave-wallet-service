package com.premisave.wallet.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Durable proof of what happened to one escrow wallet movement (see MongoEscrowWalletMovements). The same fact is
 * written on the wallet document itself in the same update as the balance; this copy survives the older wallet code
 * saving the whole wallet back, which would drop a field it does not know about.
 *
 * id is the movement key. kind is "APPLIED" when the balance was changed, "CANCELLED" when the movement was
 * ruled out and can never be applied.
 */
@Data
@Document(collection = "escrow_wallet_markers")
public class EscrowWalletMarker {

    @Id
    private String id;
    private String walletId;
    private String kind;
    private BigDecimal amount;
    private LocalDateTime createdAt;
}
