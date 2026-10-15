package com.premisave.wallet.commercial.rail.mpesa.c2b;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

/**
 * The record of one paybill payment Safaricom told us about. It is written before anything else is done with
 * the payment, so a crash can never lose money that was taken: whatever is still RECEIVED is picked up again.
 * The id is Safaricom's transaction id, so a repeated confirmation lands on the same record.
 *
 * UNMATCHED receipts are payments no merchant could be credited for (an unknown account number, a test
 * reference, a payment that arrived after being refused). They are for an operator to resolve.
 */
@Document(collection = "commercial_c2b_receipts")
@CompoundIndex(name = "status_received", def = "{'status': 1, 'receivedAt': 1}")
public class C2bReceipt {

    public enum Status { RECEIVED, RECORDED, UNMATCHED }

    @Id
    private String transId;
    private Status status;
    private String reason;
    private String billRef;
    private String amount;
    private String shortcode;
    private Map<String, Object> raw;
    private String transactionId;
    private String flag;
    private int attempts;
    private Instant receivedAt;
    private Instant processedAt;

    public C2bReceipt() {}

    public String getTransId() { return transId; }
    public void setTransId(String v) { this.transId = v; }
    public Status getStatus() { return status; }
    public void setStatus(Status v) { this.status = v; }
    public String getReason() { return reason; }
    public void setReason(String v) { this.reason = v; }
    public String getBillRef() { return billRef; }
    public void setBillRef(String v) { this.billRef = v; }
    public String getAmount() { return amount; }
    public void setAmount(String v) { this.amount = v; }
    public String getShortcode() { return shortcode; }
    public void setShortcode(String v) { this.shortcode = v; }
    public Map<String, Object> getRaw() { return raw; }
    public void setRaw(Map<String, Object> v) { this.raw = v; }
    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String v) { this.transactionId = v; }
    public String getFlag() { return flag; }
    public void setFlag(String v) { this.flag = v; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int v) { this.attempts = v; }
    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant v) { this.receivedAt = v; }
    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant v) { this.processedAt = v; }
}
