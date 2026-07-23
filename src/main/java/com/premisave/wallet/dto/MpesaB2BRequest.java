package com.premisave.wallet.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class MpesaB2BRequest {

    @NotNull
    @DecimalMin("10.00")
    private BigDecimal amount;

    /** Receiver's paybill/till shortcode — NOT a phone number. */
    @NotBlank
    private String receiverShortcode;

    /** BusinessPayBill | BusinessBuyGoods | MerchantToMerchantTransfer. Defaults to configured value if omitted. */
    private String commandId;

    private String accountReference;

    private String remarks;

    /** Idempotency key. */
    private String reference;
}