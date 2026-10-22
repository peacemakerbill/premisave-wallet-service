package com.premisave.wallet.dto;

import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.EscrowHoldStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Result of POST /internal/escrow/hold. */
@Data
public class EscrowHoldResponse {
    private String reference;
    private EscrowHoldStatus status;
    private String senderEmail;
    private String beneficiaryEmail;
    private BigDecimal amount;
    private BigDecimal commission;
    private BigDecimal totalDebited;
    private BigDecimal remainingAmount;
    private Currency currency;
    private LocalDateTime createdAt;
}
