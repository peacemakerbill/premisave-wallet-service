package com.premisave.wallet.commercial.dispute;

import com.premisave.wallet.commercial.enums.RailId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * A customer's challenge to a payment (section 2.16). The disputed amount is held on the merchant
 * balance while the dispute is open: released when the merchant wins, taken when it is lost.
 * Changes go through guarded updates in DisputeService, each applied once.
 */
@Document(collection = "commercial_disputes")
@CompoundIndexes({
    @CompoundIndex(name = "merchant_env_status_created", def = "{'merchantId': 1, 'livemode': 1, 'status': 1, 'createdAt': -1}"),
    @CompoundIndex(name = "provider_dispute", def = "{'rail': 1, 'providerDisputeId': 1}", unique = true, sparse = true),
    @CompoundIndex(name = "unfinished", def = "{'opened': 1, 'settled': 1, 'updatedAt': 1}")
})
public class Dispute {

    public enum Status {
        NEEDS_EVIDENCE, UNDER_REVIEW, WON, LOST;

        public boolean isFinal() {
            return this == WON || this == LOST;
        }
    }

    /** A dispute that is still being decided. */
    public static final Set<Status> ACTIVE = EnumSet.of(Status.NEEDS_EVIDENCE, Status.UNDER_REVIEW);

    public static class Attachment {
        private String name;
        private String url;
        private String mimeType;

        public Attachment() {}

        public Attachment(String name, String url, String mimeType) {
            this.name = name;
            this.url = url;
            this.mimeType = mimeType;
        }

        public String getName() { return name; }
        public String getUrl() { return url; }
        public String getMimeType() { return mimeType; }
    }

    public static class Evidence {
        /** Not called id, so the mapping layer does not treat it as the document key. */
        private String evidenceId;
        private String type;
        private String text;
        private List<Attachment> attachments = new ArrayList<>();
        private boolean submitted;
        private Instant createdAt;
        private Instant submittedAt;

        public Evidence() {}

        public Evidence(String id, String type, String text, List<Attachment> attachments, boolean submitted,
                        Instant createdAt, Instant submittedAt) {
            this.evidenceId = id;
            this.type = type;
            this.text = text;
            this.attachments = attachments == null ? new ArrayList<>() : new ArrayList<>(attachments);
            this.submitted = submitted;
            this.createdAt = createdAt;
            this.submittedAt = submittedAt;
        }

        public String getId() { return evidenceId; }
        public String getType() { return type; }
        public String getText() { return text; }
        public List<Attachment> getAttachments() { return attachments; }
        public boolean isSubmitted() { return submitted; }
        public Instant getCreatedAt() { return createdAt; }
        public Instant getSubmittedAt() { return submittedAt; }

        /** The same entry marked as sent to the provider at the given time. */
        public Evidence sentAt(Instant at) {
            return new Evidence(evidenceId, type, text, attachments, true, createdAt, at);
        }
    }

    @Id
    private String id;

    private String merchantId;
    private String appId;
    private boolean livemode;

    @Indexed
    private String paymentId;

    private Status status = Status.NEEDS_EVIDENCE;
    private long amountCents;
    private String reason;
    private RailId rail;
    private String providerDisputeId;
    private Instant evidenceDueBy;
    private List<Evidence> evidence = new ArrayList<>();

    private String holdId;
    private long heldCents;
    /** What could not be taken from the merchant balance when the dispute was lost. */
    private long unrecoveredCents;

    /** The hold and the opened event are done. */
    private boolean opened;
    /** The money and the won or lost event are done. */
    private boolean settled;

    private long version = 1;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant resolvedAt;

    public Dispute() {}

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String v) { this.merchantId = v; }
    public String getAppId() { return appId; }
    public void setAppId(String v) { this.appId = v; }
    public boolean isLivemode() { return livemode; }
    public void setLivemode(boolean v) { this.livemode = v; }
    public String getPaymentId() { return paymentId; }
    public void setPaymentId(String v) { this.paymentId = v; }
    public Status getStatus() { return status; }
    public void setStatus(Status v) { this.status = v; }
    public long getAmountCents() { return amountCents; }
    public void setAmountCents(long v) { this.amountCents = v; }
    public String getReason() { return reason; }
    public void setReason(String v) { this.reason = v; }
    public RailId getRail() { return rail; }
    public void setRail(RailId v) { this.rail = v; }
    public String getProviderDisputeId() { return providerDisputeId; }
    public void setProviderDisputeId(String v) { this.providerDisputeId = v; }
    public Instant getEvidenceDueBy() { return evidenceDueBy; }
    public void setEvidenceDueBy(Instant v) { this.evidenceDueBy = v; }
    public List<Evidence> getEvidence() { return evidence; }
    public void setEvidence(List<Evidence> v) { this.evidence = v == null ? new ArrayList<>() : v; }
    public String getHoldId() { return holdId; }
    public long getHeldCents() { return heldCents; }
    public long getUnrecoveredCents() { return unrecoveredCents; }
    public boolean isOpened() { return opened; }
    public boolean isSettled() { return settled; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
    public Instant getResolvedAt() { return resolvedAt; }
}
