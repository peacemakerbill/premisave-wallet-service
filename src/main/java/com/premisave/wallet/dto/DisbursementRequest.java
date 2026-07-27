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
     *  - MPESA  → IGNORED. The recipient phone number is always resolved
     *             from the caller's own verified profile (see
     *             DisbursementService.resolveVerifiedPhoneNumber) — never
     *             taken from this field. Omit it entirely for MPESA requests.
     *  - PAYPAL → PayPal email address (required)
     *  - STRIPE → Stripe external account ID (ba_xxxx) (required)
     *
     * Not annotated @NotBlank here since it's genuinely optional for MPESA;
     * DisbursementService enforces it manually for STRIPE/PAYPAL.
     */
    private String destination;

    /**
     * Provider: MPESA | STRIPE | PAYPAL
     * Defaults to MPESA if omitted.
     */
    private String provider;

    /** ISO 4217 currency code. Defaults to KES for M-Pesa, USD for PayPal/Stripe. */
    private String currency;

    /** Optional idempotency key — generated if not provided. */
    private String reference;

    /** Optional human-readable note. */
    private String remarks;
}