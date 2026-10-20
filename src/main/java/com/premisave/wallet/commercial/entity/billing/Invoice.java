package com.premisave.wallet.commercial.entity.billing;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The bill for one billing period of a subscription (section 2.14). There is exactly one invoice per
 * subscription and billing period, enforced by a unique index, so the scheduler, a restart or two
 * instances cannot bill a period twice. Each try at collecting is an attempt backed by a payment whose
 * Idempotency-Key is built from the invoice id and attempt number, so a try cannot create two payments.
 * Changes are written with a check on version so two writers cannot overwrite each other.
 */
@Document(collection = "subscription_invoices")
@CompoundIndexes({
    @CompoundIndex(name = "subscription_period", def = "{'subscriptionId': 1, 'billingPeriod': 1}", unique = true),
    @CompoundIndex(name = "subscription_created", def = "{'subscriptionId': 1, 'createdAt': -1}"),
    @CompoundIndex(name = "retry_due", def = "{'status': 1, 'nextRetryAt': 1}")
})
public class Invoice {

    public enum Status { OPEN, AWAITING_CUSTOMER, PAID, PAYMENT_FAILED, VOID }

    public static class Attempt {
        public static final String INITIATING = "INITIATING";
        public static final String REQUIRES_ACTION = "REQUIRES_ACTION";
        public static final String PENDING = "PENDING";
        public static final String SUCCESS = "SUCCESS";
        public static final String FAILED = "FAILED";

        private int attempt;
        private Instant at;
        private String status;
        private String paymentId;
        private String paymentMethodId;
        private String code;
        /** What the invoice looked like before this attempt began, so a discarded attempt can be undone. */
        private Status priorStatus;
        private Instant priorNextRetryAt;
        /** False for a failure caused by a provider outage, which does not use up a retry. */
        private boolean counted = true;

        public Attempt() {}

        public Attempt(int attempt, Instant at, String paymentMethodId, Status priorStatus, Instant priorNextRetryAt) {
            this.attempt = attempt;
            this.at = at;
            this.status = INITIATING;
            this.paymentMethodId = paymentMethodId;
            this.priorStatus = priorStatus;
            this.priorNextRetryAt = priorNextRetryAt;
        }

        public boolean isFinal() {
            return SUCCESS.equals(status) || FAILED.equals(status);
        }

        public int getAttempt() { return attempt; }
        public Instant getAt() { return at; }
        public String getStatus() { return status; }
        public void setStatus(String v) { this.status = v; }
        public String getPaymentId() { return paymentId; }
        public void setPaymentId(String v) { this.paymentId = v; }
        public String getPaymentMethodId() { return paymentMethodId; }
        public String getCode() { return code; }
        public void setCode(String v) { this.code = v; }
        public Status getPriorStatus() { return priorStatus; }
        public Instant getPriorNextRetryAt() { return priorNextRetryAt; }
        public boolean isCounted() { return counted; }
        public void setCounted(boolean v) { this.counted = v; }
    }

    @Id
    private String id;

    private String subscriptionId;
    private String merchantId;
    private boolean livemode;
    private String billingPeriod;
    private Instant periodStart;
    private Instant periodEnd;
    private Instant dueAt;
    private Status status = Status.OPEN;
    private BigDecimal amount;
    private String currency;
    private int quantity = 1;
    private long feeCents;
    private List<Attempt> attempts = new ArrayList<>();
    private Instant nextRetryAt;
    private String paymentId;
    /** Copied from the plan terms when the invoice is created, so later plan changes do not alter it. */
    private List<Integer> retryScheduleDays = new ArrayList<>();
    private Plan.OnExhausted onExhausted = Plan.OnExhausted.CANCEL;
    private Instant paidAt;
    private long version = 1;
    private Instant createdAt;
    private Instant updatedAt;

    public Invoice() {}

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getSubscriptionId() { return subscriptionId; }
    public void setSubscriptionId(String v) { this.subscriptionId = v; }
    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String v) { this.merchantId = v; }
    public boolean isLivemode() { return livemode; }
    public void setLivemode(boolean v) { this.livemode = v; }
    public String getBillingPeriod() { return billingPeriod; }
    public void setBillingPeriod(String v) { this.billingPeriod = v; }
    public Instant getPeriodStart() { return periodStart; }
    public void setPeriodStart(Instant v) { this.periodStart = v; }
    public Instant getPeriodEnd() { return periodEnd; }
    public void setPeriodEnd(Instant v) { this.periodEnd = v; }
    public Instant getDueAt() { return dueAt; }
    public void setDueAt(Instant v) { this.dueAt = v; }
    public Status getStatus() { return status; }
    public void setStatus(Status v) { this.status = v; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal v) { this.amount = v; }
    public String getCurrency() { return currency; }
    public void setCurrency(String v) { this.currency = v; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int v) { this.quantity = v; }
    public long getFeeCents() { return feeCents; }
    public void setFeeCents(long v) { this.feeCents = v; }
    public List<Attempt> getAttempts() { return attempts; }
    public void setAttempts(List<Attempt> v) { this.attempts = v; }
    public Instant getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(Instant v) { this.nextRetryAt = v; }
    public String getPaymentId() { return paymentId; }
    public void setPaymentId(String v) { this.paymentId = v; }
    public List<Integer> getRetryScheduleDays() { return retryScheduleDays; }
    public void setRetryScheduleDays(List<Integer> v) { this.retryScheduleDays = v; }
    public Plan.OnExhausted getOnExhausted() { return onExhausted; }
    public void setOnExhausted(Plan.OnExhausted v) { this.onExhausted = v; }
    public Instant getPaidAt() { return paidAt; }
    public void setPaidAt(Instant v) { this.paidAt = v; }
    public long getVersion() { return version; }
    public void setVersion(long v) { this.version = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
