package com.premisave.wallet.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Request to top up a B2C disbursement shortcode's utility account from
 * Premisave's working account (MMF), via CommandID "BusinessPayToBulk".
 * See https://developer.safaricom.co.ke/apis/B2CAccountTopUp
 */
@Data
public class B2CTopUpRequest {

    @NotNull
    @DecimalMin("10.00")
    private BigDecimal amount;

    /** The B2C shortcode being topped up. Defaults to mpesa.daraja.b2c.shortcode if omitted. */
    private String receivingShortcode;

    /** Optional consumer MSISDN this top-up is being made on behalf of. */
    private String requester;

    private String accountReference;

    private String remarks;

    /** Optional idempotency key — generated if not provided. */
    private String reference;
}