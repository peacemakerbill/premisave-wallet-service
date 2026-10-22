package com.premisave.wallet.dto;

import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.EscrowHoldStatus;
import com.premisave.wallet.enums.EscrowPayoutStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Result of GET /internal/escrow/{holdReference}: the hold and every payout made from it. */
@Data
public class EscrowDetailsResponse {
    private String reference;
    private EscrowHoldStatus status;
    private String senderEmail;
    private String beneficiaryEmail;
    private BigDecimal amount;
    private BigDecimal commission;
    private BigDecimal totalDebited;
    private BigDecimal remainingAmount;
    private Currency currency;
    private String failureReason;
    private LocalDateTime createdAt;
    private List<Payout> payouts = new ArrayList<>();

    @Data
    public static class Payout {
        private String reference;
        private String recipientEmail;
        private BigDecimal amount;
        private EscrowPayoutStatus status;
        private String failureReason;
        private LocalDateTime createdAt;
    }
}
