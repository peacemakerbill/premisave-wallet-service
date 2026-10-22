package com.premisave.wallet.dto;

import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.EscrowHoldStatus;
import com.premisave.wallet.enums.EscrowPayoutStatus;
import lombok.Data;

import java.math.BigDecimal;

/** Result of POST /internal/escrow/{holdReference}/payouts. */
@Data
public class EscrowPayoutResponse {
    private String holdReference;
    private String reference;
    private EscrowPayoutStatus status;
    private String recipientEmail;
    private BigDecimal amount;
    private BigDecimal holdRemainingAmount;
    private EscrowHoldStatus holdStatus;
    private Currency currency;
}
