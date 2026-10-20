package com.premisave.wallet.commercial.entity.onboarding;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** A named, ever increasing counter. The merchant number counter lives here under the id merchant_number. */
@Document(collection = "commercial_counters")
public class CommercialCounter {

    @Id
    private String id;
    private long seq;

    public CommercialCounter() {}

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public long getSeq() { return seq; }
    public void setSeq(long v) { this.seq = v; }
}
