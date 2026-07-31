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
     *
     * NOTE: this is a manually-typed, unverified destination — distinct from
     * paypalConnectedEmail below, which PayPal itself confirms via Vault.
     */
    private String paypalEmail;

    /**
     * M-Pesa phone number the user wants deposits (quick STK push reloads)
     * and disbursements sent to. Resolved authoritatively from here — never
     * taken from a deposit/disbursement request itself — same reasoning as
     * paypalEmail: eliminates typo/mistargeted-payout risk and lets the user
     * pick which verified number to use. If unset, disbursements fall back
     * to the auth-service profile's phone number (see
     * DisbursementService.resolveVerifiedPhoneNumber); deposits require the
     * caller to supply a phoneNumber explicitly instead.
     * Stored normalized to 254XXXXXXXXX. Set via PUT /wallet/mpesa-phone.
     */
    private String mpesaPhoneNumber;

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

    /**
     * PayPal Vault payment token id (see PaypalService Vault methods) for
     * this wallet's saved PayPal account — lets returning depositors pay
     * without a full re-approval flow. Set after a successful vaulted
     * capture (see DepositService.confirmPaypalDepositInternal) or, if
     * vaulting was still processing at capture time (vault.status
     * "APPROVED" rather than "VAULTED"), finalized later via the
     * VAULT.PAYMENT-TOKEN.CREATED webhook (see
     * DepositService.attachPaypalVaultToken). Sandbox and production
     * vault tokens are entirely separate, same caveat as Stripe above.
     */
    @Indexed(sparse = true)
    private String paypalVaultId;

    /**
     * PayPal-generated customer.id tied to this wallet's saved payment
     * source(s). Passed back to PayPal on subsequent createOrder calls
     * alongside paypalVaultId, and used to resolve which wallet a
     * VAULT.PAYMENT-TOKEN.CREATED webhook belongs to when vaulting
     * finalizes asynchronously (see WalletRepository.findByPaypalCustomerId).
     */
    @Indexed(unique = true, sparse = true)
    private String paypalCustomerId;

    /**
     * Display-only — the PayPal account email PayPal itself confirmed
     * during vaulting (returned alongside vault.id at capture). Distinct
     * from paypalEmail above, which is a manually-typed, unverified payout
     * destination. Safe to show in the frontend as "Connected: user@example.com".
     */
    private String paypalConnectedEmail;

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}