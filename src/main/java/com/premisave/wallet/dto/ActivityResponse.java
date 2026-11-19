package com.premisave.wallet.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One line of a person's wallet activity. [type] is DEPOSIT, WITHDRAWAL, TRANSFER, PAYMENT or REFUND, [direction] is IN
 * when money came into the wallet and OUT when it left, and [status] is the status word of the record it came from.
 */
@Data
@AllArgsConstructor
public class ActivityResponse {
    private String id;
    private String type;
    private String direction;
    private String status;
    private BigDecimal amount;
    private String currency;
    private String description;
    private String provider;
    private LocalDateTime createdAt;
}
