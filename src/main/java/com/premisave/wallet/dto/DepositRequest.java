package com.premisave.wallet.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class DepositRequest {

    @NotNull
    @DecimalMin("1.00")
    private BigDecimal amount;

    /**
     * Payment provider: MPESA | STRIPE | PAYPAL
     * Defaults to MPESA if omitted.
     */
    private String provider;

    /**
     * Required for M-Pesa STK push — the customer's Safaricom number.
     * Format: 07xxxxxxxx or 254xxxxxxxx
     */
    private String phoneNumber;

    /**
     * ISO 4217 currency code (e.g. KES, USD, EUR).
     * Defaults to KES for M-Pesa, USD for PayPal.
     */
    private String currency;
}