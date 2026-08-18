package com.premisave.wallet.entity;

import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.PaymentStatus;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Wallet-to-platform payment tracking (e.g. a homeowner's ad subscription,
 * a booking fee) — mirrors Deposit/Disbursement/Transfer: a dedicated
 * entity instead of the bare Transaction row PaymentService used to
 * create with no separate lifecycle record of the payment itself.
 *
 * Distinct from Transfer: only ONE wallet is touched — funds leave the
 * system entirely (to Premisave itself) rather than moving to another
 * user's wallet, so there's no sender/recipient pair, just a single payer.
 *
 * Same PENDING-before-mutation behavior change as Transfer — see
 * Transfer's javadoc for why this means a failed payment attempt now
 * leaves a real FAILED record instead of no trace at all.
 */
@Data
@Document(collection = "payments")
public class Payment {

    @Id
    private String id;

    private String userId;
    private String walletId;

    private BigDecimal amount;
    private Currency currency;

    /** Category of what's being paid for (e.g. "AD_SUBSCRIPTION", "BOOKING_FEE") — see PaymentInitiateRequest.service. */
    private String service;

    /** Specific, human-readable detail beyond the category (e.g. "August 2026 ad subscription") — new, not previously captured at all. */
    private String description;

    private PaymentStatus status = PaymentStatus.PENDING;

    private String reference;

    private String failureReason;

    /** Same role as Transfer.initiatedBy — "USER" or the calling service's name. */
    private String initiatedBy;

    @CreatedDate
    private LocalDateTime createdAt;
}