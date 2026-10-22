package com.premisave.wallet.entity;

import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.EscrowPayoutStatus;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One payment out of an escrow hold, to the hold's sender (a refund) or to its beneficiary (the owner).
 * No commission is charged on a payout. Like EscrowHold, the unique index on reference makes it an
 * idempotency key, and the document is only changed through guarded updates after creation.
 */
@Data
@Document(collection = "escrow_payouts")
@CompoundIndex(name = "status_pendingSince", def = "{'status': 1, 'pendingSince': 1}")
public class EscrowPayout {

    @Id
    private String id;

    @Indexed(unique = true)
    private String reference;

    @Indexed
    private String holdId;
    private String holdReference;

    /** "SENDER" (a refund) or "BENEFICIARY" (the owner's share). */
    private String recipientRole;
    private String recipientUserId;
    private String recipientWalletId;
    private String recipientEmail;

    private BigDecimal amount;
    private Currency currency = Currency.USD;

    private EscrowPayoutStatus status = EscrowPayoutStatus.PENDING;

    private String description;
    private String initiatedBy;
    private String failureReason;

    /** 1 for the first try; a FAILED reference that is used again reopens the same document with attempts + 1. */
    private int attempts = 1;

    private LocalDateTime pendingSince;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
