package com.premisave.wallet.commercial.batch;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A bulk payout request. The payouts themselves are ordinary payouts that carry the batch id in their
 * relations; this document holds the request level facts and what was turned away at intake.
 * uniqueKey is set only while the batch is not rejected, so a rejected batch's reference can be reused
 * but two live batches cannot share a reference.
 */
@Document(collection = "commercial_payout_batches")
@CompoundIndexes({
    @CompoundIndex(name = "merchant_env_created", def = "{'merchantId': 1, 'livemode': 1, 'createdAt': -1}"),
    @CompoundIndex(name = "status_updated", def = "{'status': 1, 'updatedAt': 1}"),
    @CompoundIndex(name = "event_pending", def = "{'eventEmitted': 1, 'status': 1}")
})
public class PayoutBatch {

    public enum Mode { ALL_OR_NOTHING, BEST_EFFORT }

    public enum Status { PROCESSING, COMPLETED, COMPLETED_WITH_FAILURES, REJECTED }

    /** An item that was not accepted. code is an ErrorCode name, or BATCH_REJECTED for valid items of a rejected ALL_OR_NOTHING batch. */
    public static class Rejection {
        private String merchantReference;
        private String code;
        private String message;

        public Rejection() {}

        public Rejection(String merchantReference, String code, String message) {
            this.merchantReference = merchantReference;
            this.code = code;
            this.message = message;
        }

        public String getMerchantReference() { return merchantReference; }
        public String getCode() { return code; }
        public String getMessage() { return message; }
    }

    @Id
    private String batchId;

    @Indexed(unique = true, sparse = true)
    private String uniqueKey;

    private String merchantId;
    private String appId;
    private boolean livemode;
    private String merchantReference;
    private Mode mode;
    private Status status = Status.PROCESSING;
    private String callbackUrl;
    private int requestedCount;
    private int acceptedCount;
    private long totalAmountCents;
    private long totalFeeCents;
    private List<Rejection> rejected = new ArrayList<>();
    private boolean eventEmitted;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant completedAt;

    public PayoutBatch() {}

    public static String uniqueKeyFor(String merchantId, boolean livemode, String merchantReference) {
        return merchantId + "|" + (livemode ? "live" : "sandbox") + "|" + merchantReference;
    }

    public String getBatchId() { return batchId; }
    public void setBatchId(String v) { this.batchId = v; }
    public String getUniqueKey() { return uniqueKey; }
    public void setUniqueKey(String v) { this.uniqueKey = v; }
    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String v) { this.merchantId = v; }
    public String getAppId() { return appId; }
    public void setAppId(String v) { this.appId = v; }
    public boolean isLivemode() { return livemode; }
    public void setLivemode(boolean v) { this.livemode = v; }
    public String getMerchantReference() { return merchantReference; }
    public void setMerchantReference(String v) { this.merchantReference = v; }
    public Mode getMode() { return mode; }
    public void setMode(Mode v) { this.mode = v; }
    public Status getStatus() { return status; }
    public void setStatus(Status v) { this.status = v; }
    public String getCallbackUrl() { return callbackUrl; }
    public void setCallbackUrl(String v) { this.callbackUrl = v; }
    public int getRequestedCount() { return requestedCount; }
    public void setRequestedCount(int v) { this.requestedCount = v; }
    public int getAcceptedCount() { return acceptedCount; }
    public void setAcceptedCount(int v) { this.acceptedCount = v; }
    public long getTotalAmountCents() { return totalAmountCents; }
    public void setTotalAmountCents(long v) { this.totalAmountCents = v; }
    public long getTotalFeeCents() { return totalFeeCents; }
    public void setTotalFeeCents(long v) { this.totalFeeCents = v; }
    public List<Rejection> getRejected() { return rejected; }
    public void setRejected(List<Rejection> v) { this.rejected = v == null ? new ArrayList<>() : v; }
    public boolean isEventEmitted() { return eventEmitted; }
    public void setEventEmitted(boolean v) { this.eventEmitted = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant v) { this.completedAt = v; }
}
