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

    /**
     * If true, DisbursementService runs a "B2B Hakikisha" (Query Org Info)
     * check against receiverShortcode before sending any money — if the
     * organization name can't be confirmed, the payment is aborted before
     * Safaricom's B2B endpoint is ever called. Defaults to false so existing
     * callers are unaffected; recommended for anything paying a
     * receiverShortcode that isn't already a trusted, known partner.
     */
    private boolean verifyRecipient = false;

    /**
     * IdentifierType for the Hakikisha check when verifyRecipient=true.
     * "2" = Lipa na M-PESA till number, "4" = PayBill/other shortcode.
     * Defaults to "4" since receiverShortcode is a paybill in the common case.
     */
    private String receiverIdentifierTypeForVerification = "4";
}