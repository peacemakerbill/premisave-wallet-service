package com.premisave.wallet.entity;

import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.DisbursementStatus;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Document(collection = "disbursements")
public class Disbursement {

    @Id
    private String id;

    private String userId;
    private String walletId;

    private BigDecimal amount;
    private Currency currency;

    private String destination; // phone number, paypal email, receiver shortcode, etc.
    private String provider;    // MPESA, PAYPAL, STRIPE
    private String channel;     // B2C, B2B, PAYPAL_PAYOUT, STRIPE_PAYOUT — null for legacy rows

    private DisbursementStatus status = DisbursementStatus.PENDING;

    private String reference;

    @Indexed
    private String providerReference; // ConversationID (M-Pesa) — used to reconcile async result callbacks

    private String failureReason;

    @CreatedDate
    private LocalDateTime createdAt;
}