package com.premisave.wallet.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class DisbursementRequest {

    @NotNull
    @DecimalMin("1.00")
    private BigDecimal amount;

    /**
     * Destination identifier:
     *  - MPESA       → IGNORED. The recipient phone number is always resolved
     *                  from the caller's own verified profile (see
     *                  DisbursementService.resolveVerifiedPhoneNumber) — never
     *                  taken from this field. Omit it entirely for MPESA requests.
     *  - PAYPAL      → PayPal email address (required)
     *  - STRIPE      → IGNORED. The destination is the wallet's linked Stripe
     *                  Connect account (see POST /wallet/stripe/connect/link)
     *                  — resolved from the caller's own wallet, never taken
     *                  from this field, same reasoning as MPESA above. Omit
     *                  it entirely for STRIPE requests.
     *  - FLUTTERWAVE → IGNORED. Use flutterwaveAccountBank/flutterwaveAccountNumber
     *                  instead — a bank/mobile-money transfer needs a
     *                  two-part destination, not a single string.
     *  - NOWPAYMENTS → Required — the recipient's crypto wallet address.
     *                  Paired with nowPaymentsCurrency below (which crypto).
     *
     * Not annotated @NotBlank here since it's genuinely optional for MPESA/
     * STRIPE/FLUTTERWAVE; DisbursementService enforces it manually per provider.
     */
    private String destination;

    /**
     * Provider: MPESA | STRIPE | PAYPAL | FLUTTERWAVE | NOWPAYMENTS
     * Defaults to MPESA if omitted.
     */
    private String provider;

    /**
     * ISO 4217 currency code. Defaults to KES for M-Pesa, USD for
     * PayPal/Flutterwave. For STRIPE, defaults to USD — the wallet's KES
     * balance is FX-converted to this currency at request time (see
     * DisbursementService.disburseStripe), same pattern as PayPal. Set to
     * whatever currency the linked connected account's bank actually
     * settles in (e.g. GBP for a UK account) if it isn't USD.
     */
    private String currency;

    /** Optional idempotency key — generated if not provided. */
    private String reference;

    /** Optional human-readable note. */
    private String remarks;

    /**
     * Required for provider=FLUTTERWAVE — the destination's Flutterwave
     * bank code (bank transfer) or mobile network code (mobile money
     * transfer, e.g. "MPS", "MTN", "AIRTEL" — see Flutterwave's per-country
     * mobile money docs and GET /banks/{country} for bank codes).
     */
    private String flutterwaveAccountBank;

    /**
     * Required for provider=FLUTTERWAVE — the destination account number
     * (bank transfer) or phone number (mobile money transfer).
     */
    private String flutterwaveAccountNumber;

    /**
     * Required for provider=FLUTTERWAVE — BANK or MOBILE_MONEY. Both use
     * the same Flutterwave Transfers endpoint; this only affects which
     * fields DisbursementService validates as required and how the
     * destination is displayed/stored on the Disbursement record.
     */
    private String flutterwaveTransferType;

    /** Optional, provider=FLUTTERWAVE only — beneficiary's display name, passed through to Flutterwave. */
    private String flutterwaveBeneficiaryName;

    /**
     * Required for provider=NOWPAYMENTS — the cryptocurrency being sent
     * (e.g. "trx", "btc", "usdttrc20"). Paired with destination above (the
     * wallet address). NOWPayments' payout endpoint takes this as its own
     * per-withdrawal currency field, separate from the KES amount field.
     */
    private String nowPaymentsCurrency;
}