package com.premisave.wallet.commercial.entity;

import com.premisave.wallet.commercial.enums.TxStatus;

import java.time.Instant;

public class TimelineEntry {

    private Instant at;
    private TxStatus status;
    private String note;

    public TimelineEntry() {}

    public TimelineEntry(Instant at, TxStatus status, String note) {
        this.at = at;
        this.status = status;
        this.note = note;
    }

    public Instant getAt() { return at; }
    public TxStatus getStatus() { return status; }
    public String getNote() { return note; }
}
