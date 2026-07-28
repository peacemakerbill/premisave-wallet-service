package com.premisave.wallet.entity;

import com.premisave.wallet.enums.Currency;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Document(collection = "wallets")
public class Wallet {

    @Id
    private String id;

    @Indexed(unique = true)
    private String accountNumber; // User's email

    private String userId;

    private BigDecimal balance = BigDecimal.ZERO;

    private Currency currency = Currency.KES;

    private boolean isFrozen = false;

    /**
     * PayPal email the user wants payouts sent to. Resolved authoritatively
     * from here for PayPal disbursements (see DisbursementService) — never
     * taken from the disbursement request itself, same reasoning as M-Pesa's
     * verified-phone-number pattern: eliminates typo/mistargeted-payout risk.
     * Set via PUT /wallet/paypal-email.
     */
    private String paypalEmail;

    /**
     * Stripe Customer object id (cus_xxx) for this wallet's owner. Created
     * lazily on first Stripe deposit or first setup-intent request. A
     * test-mode customer and a live-mode customer are entirely separate
     * objects in Stripe — this id is only valid for whichever mode
     * (sandbox/production) it was created under.
     */
    @Indexed(unique = true, sparse = true)
    private String stripeCustomerId;

    /**
     * The PaymentMethod (pm_xxx) currently saved for one-click deposit
     * reloads. Attached after a successful SetupIntent (see
     * DepositService.confirmStripeSetupIntent) or as a side effect of any
     * successful deposit that requested setup_future_usage. Same
     * sandbox/production separation caveat as stripeCustomerId.
     */
    private String stripeDefaultPaymentMethodId;

    /** Display-only — safe to store (not sensitive), lets the frontend show "Visa •••• 4242" without an extra Stripe call. */
    private String stripeCardBrand;
    private String stripeCardLast4;

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}