package com.premisave.wallet.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
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
     *  - MPESA  → phone number (07xxxxxxxx or 254xxxxxxxx)
     *  - PAYPAL → PayPal email address
     *  - STRIPE → Stripe external account ID (ba_xxxx)
     */
    @NotBlank
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