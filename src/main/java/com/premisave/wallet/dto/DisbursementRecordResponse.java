package com.premisave.wallet.dto;

import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.DisbursementStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A single Disbursement record for history display — distinct from
 * DisbursementResponse's role (the generic id/status/message shape
 * returned when a disbursement is first initiated). This is what
 * GET /disbursements/history returns: the full, already-resolved record,
 * including totalDebited/commissionRate so a user can see exactly what
 * they were charged versus what actually went out.
 */
@Data
public class DisbursementRecordResponse {
    private String id;
    private String userId;
    private BigDecimal amount;
    private BigDecimal totalDebited;
    private BigDecimal commissionRate;
    private Currency currency;
    private String destination;
    private String provider;
    private String channel;
    private DisbursementStatus status;
    private String reference;
    private String providerReference;
    private String failureReason;
    private LocalDateTime createdAt;
}