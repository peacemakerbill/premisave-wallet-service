package com.premisave.wallet.commercial.rail.premisave;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A Premisave wallet owner's say over a merchant's request. A PAYMENT consent is one debit for one commercial
 * payment; the owner approves or declines it in the Premisave app. A MANDATE consent is a standing approval
 * for a merchant to charge the wallet on a subscription, up to maxCents per charge, until the owner revokes it.
 * A payment made under a mandate is recorded as an already approved PAYMENT consent that points at the mandate.
 */
@Document(collection = "commercial_wallet_consents")
public class WalletConsent {

    public enum Kind { PAYMENT, MANDATE }

    public enum Status {
        PENDING, APPROVED, USED, DECLINED, REVOKED, EXPIRED, CANCELLED, REVERSED;

        /** True for states a consent can never leave. */
        public boolean isClosed() {
            return this == DECLINED || this == REVOKED || this == EXPIRED || this == CANCELLED || this == REVERSED;
        }
    }

    @Id
    private String id;
    private Kind kind;
    private Status status;
    @Indexed
    private String accountNumber;
    private String merchantId;
    private String merchantName;
    @Indexed(unique = true, sparse = true)
    private String transactionId;
    @Indexed(unique = true, sparse = true)
    private String paymentMethodId;
    private String mandateId;
    /** The amount to debit for a PAYMENT, the largest single charge for a MANDATE. */
    private long amountCents;
    private String description;
    private Instant createdAt;
    private Instant expiresAt;
    private Instant decidedAt;

    public WalletConsent() {}

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public Kind getKind() { return kind; }
    public void setKind(Kind v) { this.kind = v; }
    public Status getStatus() { return status; }
    public void setStatus(Status v) { this.status = v; }
    public String getAccountNumber() { return accountNumber; }
    public void setAccountNumber(String v) { this.accountNumber = v; }
    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String v) { this.merchantId = v; }
    public String getMerchantName() { return merchantName; }
    public void setMerchantName(String v) { this.merchantName = v; }
    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String v) { this.transactionId = v; }
    public String getPaymentMethodId() { return paymentMethodId; }
    public void setPaymentMethodId(String v) { this.paymentMethodId = v; }
    public String getMandateId() { return mandateId; }
    public void setMandateId(String v) { this.mandateId = v; }
    public long getAmountCents() { return amountCents; }
    public void setAmountCents(long v) { this.amountCents = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant v) { this.expiresAt = v; }
    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant v) { this.decidedAt = v; }
}
