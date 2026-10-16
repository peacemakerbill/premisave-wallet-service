package com.premisave.wallet.commercial.onboarding;

import java.time.Instant;

/** One thing a reviewer asked the applicant to correct. */
public class ReturnItem {

    private String itemId;
    /** The form field or document the item is about, for example taxPin or document:TAX_COMPLIANCE. */
    private String field;
    /** What the applicant must correct. */
    private String message;
    private boolean addressed;
    /** What the applicant said when resubmitting. */
    private String response;
    private Instant addressedAt;
    private String raisedBy;
    private Instant raisedAt;

    public ReturnItem() {}

    public String getItemId() { return itemId; }
    public void setItemId(String v) { this.itemId = v; }
    public String getField() { return field; }
    public void setField(String v) { this.field = v; }
    public String getMessage() { return message; }
    public void setMessage(String v) { this.message = v; }
    public boolean isAddressed() { return addressed; }
    public void setAddressed(boolean v) { this.addressed = v; }
    public String getResponse() { return response; }
    public void setResponse(String v) { this.response = v; }
    public Instant getAddressedAt() { return addressedAt; }
    public void setAddressedAt(Instant v) { this.addressedAt = v; }
    public String getRaisedBy() { return raisedBy; }
    public void setRaisedBy(String v) { this.raisedBy = v; }
    public Instant getRaisedAt() { return raisedAt; }
    public void setRaisedAt(Instant v) { this.raisedAt = v; }
}
