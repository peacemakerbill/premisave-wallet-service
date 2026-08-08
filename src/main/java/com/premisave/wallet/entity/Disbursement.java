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
    private String provider;    // MPESA, PAYPAL, STRIPE, FLUTTERWAVE
    private String channel;     // B2C, B2B, PAYPAL_PAYOUT, STRIPE_PAYOUT, FLUTTERWAVE_BANK, FLUTTERWAVE_MOBILE_MONEY

    private DisbursementStatus status = DisbursementStatus.PENDING;

    private String reference;

    @Indexed
    private String providerReference; // ConversationID (M-Pesa), Payout Batch ID (PayPal), Transfer ID (Flutterwave) — used to reconcile async result callbacks

    private String failureReason;

    /**
     * Populated for B2B payments where MpesaB2BRequest.verifyRecipient=true —
     * the organization name Safaricom's "B2B Hakikisha" (Query Org Info)
     * check confirmed for the receiverShortcode before the payment was sent.
     * Null if verification wasn't requested or wasn't applicable (non-B2B channels).
     */
    private String verifiedRecipientName;

    /** Charge/tariff profile ID returned by the same Hakikisha check, if any. */
    private String verifiedChargeProfileId;

    /**
     * Flutterwave transfers require a recipient object created first, then a
     * transfer referencing that recipient. If recipient creation succeeds but
     * transfer initiation fails, this caches the recipient ID so a retry can
     * reuse it and avoid creating orphaned duplicates. Populated only for
     * FLUTTERWAVE disbursements; null for all other providers.
     * See FlutterwaveService.createTransferRecipient and
     * DisbursementService.processFlutterwaveDisbursement.
     */
    private String flutterwaveRecipientId;

    @CreatedDate
    private LocalDateTime createdAt;
}