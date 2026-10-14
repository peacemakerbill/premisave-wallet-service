package com.premisave.wallet.commercial.settlement;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.DayOfWeek;
import java.time.Instant;

/**
 * How a merchant wants its balance settled automatically. One per merchant and
 * environment. lastRunKey marks the period already handled (a day, a week, a
 * minute for thresholds); claiming it with a guarded update is what stops two
 * instances of the service from settling the same period twice.
 */
@Document(collection = "commercial_settlement_schedules")
public class SettlementSchedule {

    public enum Mode { MANUAL, DAILY, WEEKLY, THRESHOLD }

    @Id
    private String id;
    private String merchantId;
    private boolean livemode;
    /** The app that saved the schedule; scheduled settlements raise events for this app's registrations. */
    private String appId;
    private Mode mode = Mode.MANUAL;
    private String destinationId;
    private long minimumCents;
    private long keepReserveCents;
    /** HH:mm in UTC, for DAILY and WEEKLY. */
    private String runAtUtc;
    private DayOfWeek dayOfWeek;
    private long thresholdCents;
    private String lastRunKey;
    private Instant lastRunAt;
    private Instant updatedAt;

    public SettlementSchedule() {}

    public static String idFor(String merchantId, boolean livemode) {
        return merchantId + "|" + (livemode ? "live" : "sandbox");
    }

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String v) { this.merchantId = v; }
    public boolean isLivemode() { return livemode; }
    public void setLivemode(boolean v) { this.livemode = v; }
    public String getAppId() { return appId; }
    public void setAppId(String v) { this.appId = v; }
    public Mode getMode() { return mode; }
    public void setMode(Mode v) { this.mode = v; }
    public String getDestinationId() { return destinationId; }
    public void setDestinationId(String v) { this.destinationId = v; }
    public long getMinimumCents() { return minimumCents; }
    public void setMinimumCents(long v) { this.minimumCents = v; }
    public long getKeepReserveCents() { return keepReserveCents; }
    public void setKeepReserveCents(long v) { this.keepReserveCents = v; }
    public String getRunAtUtc() { return runAtUtc; }
    public void setRunAtUtc(String v) { this.runAtUtc = v; }
    public DayOfWeek getDayOfWeek() { return dayOfWeek; }
    public void setDayOfWeek(DayOfWeek v) { this.dayOfWeek = v; }
    public long getThresholdCents() { return thresholdCents; }
    public void setThresholdCents(long v) { this.thresholdCents = v; }
    public String getLastRunKey() { return lastRunKey; }
    public void setLastRunKey(String v) { this.lastRunKey = v; }
    public Instant getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(Instant v) { this.lastRunAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
