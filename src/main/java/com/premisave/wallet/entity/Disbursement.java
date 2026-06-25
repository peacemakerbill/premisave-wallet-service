package com.premisave.wallet.entity;

import com.premisave.wallet.enums.Currency;
import com.premisave.wallet.enums.DisbursementStatus;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Document(collection = "disbursements")
public class Disbursement {

    @Id
    private String id;

    private String userId;
    private String walletId;

    private BigDecimal amount;
    private Currency currency;

    private String destination; // phone number, paypal email, etc.
    private String provider; // MPESA, PAYPAL, STRIPE

    private DisbursementStatus status = DisbursementStatus.PENDING;

    private String reference;
    private String providerReference;

    @CreatedDate
    private LocalDateTime createdAt;
}