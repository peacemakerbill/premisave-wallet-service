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

    /** The other side of a transfer: the user id and wallet email of whoever sent or received it. Empty for other kinds. */
    private String counterpartyId;
    private String counterpartyEmail;

    /** A name for the other side when one is known, for example the verified name behind an M-Pesa number. */
    private String counterpartyName;

    /** Where the money came from or went to outside the wallet, for example a phone number or a PayPal email. */
    private String account;

    private String reference;
    private String failureReason;

    public ActivityResponse(String id, String type, String direction, String status, BigDecimal amount, String currency,
                            String description, String provider, LocalDateTime createdAt) {
        this(id, type, direction, status, amount, currency, description, provider, createdAt,
                null, null, null, null, null, null);
    }

    /** Adds the details a screen shows when a line is opened. Returns this, so it chains onto the constructor. */
    public ActivityResponse with(String counterpartyId, String counterpartyEmail, String counterpartyName, String account,
                                 String reference, String failureReason) {
        this.counterpartyId = counterpartyId;
        this.counterpartyEmail = counterpartyEmail;
        this.counterpartyName = counterpartyName;
        this.account = account;
        this.reference = reference;
        this.failureReason = failureReason;
        return this;
    }
}
