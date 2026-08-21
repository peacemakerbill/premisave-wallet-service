package com.premisave.wallet.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A single pending item, generically, regardless of which entity it
 * actually is — the whole point of the dashboard this powers
 * (GET /admin/reconciliation/pending) is discovery across everything at
 * once, not a full detail view of any one entity. resolvableViaApi/
 * resolutionHint tell the admin what to actually do next: hit a real
 * endpoint, or (for Transfer/Payment, and M-Pesa's non-Reversal
 * operation types) that no safe automated resolution exists yet and why.
 */
@Data
public class PendingReconciliationItem {
    private String entityType;
    private String id;
    private String userId;
    private BigDecimal amount;
    private String provider;
    private String reference;
    private LocalDateTime createdAt;
    private long minutesPending;
    private boolean resolvableViaApi;
    private String resolutionHint;
}