package com.premisave.wallet.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Business-to-Pochi payment: disburses from our B2C shortcode straight into
 * a customer's "Pochi la Biashara" business wallet (CommandID BusinessPayToPochi).
 * See https://developer.safaricom.co.ke/apis/BusinessToPochi
 */
@Data
public class B2PochiRequest {

    @NotNull
    @DecimalMin("10.00")
    private BigDecimal amount;

    /** Recipient's M-Pesa MSISDN, e.g. 07xxxxxxxx or 254xxxxxxxx. */
    @NotBlank
    private String phoneNumber;

    private String remarks;

    private String occasion;

    /** Optional idempotency key — generated if not provided. */
    private String reference;
}