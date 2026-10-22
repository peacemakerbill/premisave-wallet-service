package com.premisave.wallet.entity;

import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.EscrowHoldStatus;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Money held for a booking (or any similar two party deal) by wallet-service itself, in no user's wallet.
 * The sender pays amount plus the commission once, when the hold is created; the amount then sits here until
 * it is paid out, with no further commission (see EscrowService).
 *
 * The unique index on reference is what makes the reference an idempotency key: two requests with the same
 * reference can never both create a hold.
 *
 * IMPORTANT: after creation this document is only ever changed through guarded single document updates
 * (see MongoEscrowStore), never by saving the whole entity back, because remainingAmount and
 * reservedPayoutIds are changed concurrently by payouts.
 */
@Data
@Document(collection = "escrow_holds")
@CompoundIndex(name = "status_pendingSince", def = "{'status': 1, 'pendingSince': 1}")
public class EscrowHold {

    @Id
    private String id;

    /** The caller's idempotency key, e.g. "booking-7f3a". */
    @Indexed(unique = true)
    private String reference;

    private String senderUserId;
    private String senderWalletId;
    private String senderEmail;

    /** Who is expected to be paid at the end (the owner). Checked at hold time so a booking cannot be paid for when the owner cannot be paid. */
    private String beneficiaryUserId;
    private String beneficiaryWalletId;
    private String beneficiaryEmail;

    /** What is held, the booking price P. */
    private BigDecimal amount;

    /** Commission charged on top, fee(P). Kept by the company, never refunded. */
    private BigDecimal commission;

    /** The commission rate that was applied, so a later rate change cannot rewrite history. */
    private BigDecimal commissionRate;

    /** What left the sender's wallet: amount plus commission. */
    private BigDecimal totalDebited;

    /** What is still held. Already excludes payouts that are in progress. */
    private BigDecimal remainingAmount;

    private Currency currency = Currency.USD;

    private EscrowHoldStatus status = EscrowHoldStatus.PENDING;

    private String description;
    private String initiatedBy;
    private String failureReason;

    /** 1 for the first try; a FAILED reference that is used again reopens the same document with attempts + 1. */
    private int attempts = 1;

    /** Ids of the payouts whose amount has been taken out of remainingAmount. Written in the same update as remainingAmount. */
    private List<String> reservedPayoutIds = new ArrayList<>();

    /** When the current attempt began. A hold still PENDING long after this is settled by the recovery sweep. */
    private LocalDateTime pendingSince;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
