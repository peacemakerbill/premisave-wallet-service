package com.premisave.wallet.commercial.billing;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A customer on a plan (section 2.13). It holds a copy of the plan's terms taken when it started, the
 * billing schedule, and the state. Billing is in advance: when a period starts its invoice is created and
 * charged, and nextBillingAt is the start of the following period.
 *
 * The schedule is anchor plus periodIndex periods: period k starts at anchor + k intervals, which keeps
 * month ends from drifting (31 January, 28 February, 31 March). A change that restarts the schedule (a
 * plan switch at a period boundary, resuming with billNow) sets a new anchor and bumps generation, which
 * keeps invoice period labels unique.
 *
 * All changes after creation are guarded updates in SubscriptionTransitions, BillingEngine and
 * SubscriptionService, never a save of the whole document.
 */
@Document(collection = "commercial_subscriptions")
@CompoundIndexes({
    @CompoundIndex(name = "merchant_env_state_created", def = "{'merchantId': 1, 'livemode': 1, 'state': 1, 'createdAt': -1}"),
    @CompoundIndex(name = "merchant_env_customer", def = "{'merchantId': 1, 'livemode': 1, 'customerId': 1}"),
    @CompoundIndex(name = "due", def = "{'state': 1, 'nextBillingAt': 1}"),
    @CompoundIndex(name = "paused_resume", def = "{'state': 1, 'resumeAt': 1}", sparse = true)
})
public class Subscription {

    public enum State { PENDING_AUTHORISATION, TRIALING, ACTIVE, PAST_DUE, PAUSED, CANCELLED, EXPIRED;

        public boolean isTerminal() {
            return this == CANCELLED || this == EXPIRED;
        }
    }

    /** States in which a new period is billed when its boundary arrives. */
    public static final Set<State> BILLABLE = EnumSet.of(State.TRIALING, State.ACTIVE);
    /** States in which an unpaid invoice may still be retried. */
    public static final Set<State> RETRYABLE = EnumSet.of(State.TRIALING, State.ACTIVE, State.PAST_DUE);
    public static final Set<State> LIVE = EnumSet.of(State.PENDING_AUTHORISATION, State.TRIALING, State.ACTIVE,
            State.PAST_DUE, State.PAUSED);

    /** The plan's terms as they were when the subscription (or its current plan) began. */
    public static class Terms {
        private String planName;
        private BigDecimal amount;
        private String currency;
        private Plan.Interval interval;
        private int intervalCount = 1;
        private BigDecimal setupFee = BigDecimal.ZERO;
        private List<Integer> retryScheduleDays = new ArrayList<>();
        private Plan.OnExhausted onExhausted = Plan.OnExhausted.CANCEL;

        public Terms() {}

        public static Terms of(Plan p) {
            Terms t = new Terms();
            t.planName = p.getName();
            t.amount = p.getAmount();
            t.currency = p.getCurrency();
            t.interval = p.getInterval();
            t.intervalCount = p.getIntervalCount();
            t.setupFee = p.getSetupFee() == null ? BigDecimal.ZERO : p.getSetupFee();
            t.retryScheduleDays = new ArrayList<>(p.getRetryScheduleDays());
            t.onExhausted = p.getOnExhausted();
            return t;
        }

        public String getPlanName() { return planName; }
        public BigDecimal getAmount() { return amount; }
        public String getCurrency() { return currency; }
        public Plan.Interval getInterval() { return interval; }
        public int getIntervalCount() { return intervalCount; }
        public BigDecimal getSetupFee() { return setupFee; }
        public List<Integer> getRetryScheduleDays() { return retryScheduleDays; }
        public Plan.OnExhausted getOnExhausted() { return onExhausted; }
    }

    @Id
    private String id;

    private String merchantId;
    private String appId;
    private boolean livemode;
    private String customerId;
    private String planId;
    private String paymentMethodId;
    private State state = State.ACTIVE;
    /** The state a pause interrupted, restored when the subscription resumes. */
    private State statePrePause;
    private int quantity = 1;

    private Terms terms;
    private Terms pendingTerms;
    private String pendingPlanId;

    private Instant requestedStartAt;
    private int trialDays;
    private Instant trialEnd;

    private Instant anchor;
    private int generation;
    /** Index of the latest period that has an invoice; -1 before the first. */
    private long periodIndex = -1;
    private Instant periodStart;
    private Instant periodEnd;
    private Instant nextBillingAt;
    private boolean setupFeeBilled;
    /** True once invoice.upcoming was sent for the current nextBillingAt; reset whenever that date changes. */
    private boolean upcomingNotified;

    private boolean cancelAtPeriodEnd;
    private String cancelReason;
    private Instant cancelledAt;
    private Instant pausedAt;
    private Instant resumeAt;
    private String pauseReason;

    /** Sandbox only: the latest time the subscription's clock was moved to. */
    private Instant clockAt;

    private String callbackUrl;
    private Map<String, String> metadata = new LinkedHashMap<>();

    /** One live subscription per customer and plan: set while live, removed when it ends. */
    @Indexed(unique = true, sparse = true)
    private String activeKey;

    private long version = 1;
    private Instant createdAt;
    private Instant updatedAt;

    public Subscription() {}

    public static String activeKeyFor(String merchantId, boolean livemode, String customerId, String planId) {
        return merchantId + "|" + (livemode ? "live" : "sandbox") + "|" + customerId + "|" + planId;
    }

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String v) { this.merchantId = v; }
    public String getAppId() { return appId; }
    public void setAppId(String v) { this.appId = v; }
    public boolean isLivemode() { return livemode; }
    public void setLivemode(boolean v) { this.livemode = v; }
    public String getCustomerId() { return customerId; }
    public void setCustomerId(String v) { this.customerId = v; }
    public String getPlanId() { return planId; }
    public void setPlanId(String v) { this.planId = v; }
    public String getPaymentMethodId() { return paymentMethodId; }
    public void setPaymentMethodId(String v) { this.paymentMethodId = v; }
    public State getState() { return state; }
    public void setState(State v) { this.state = v; }
    public State getStatePrePause() { return statePrePause; }
    public void setStatePrePause(State v) { this.statePrePause = v; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int v) { this.quantity = v; }
    public Terms getTerms() { return terms; }
    public void setTerms(Terms v) { this.terms = v; }
    public Terms getPendingTerms() { return pendingTerms; }
    public void setPendingTerms(Terms v) { this.pendingTerms = v; }
    public String getPendingPlanId() { return pendingPlanId; }
    public void setPendingPlanId(String v) { this.pendingPlanId = v; }
    public Instant getRequestedStartAt() { return requestedStartAt; }
    public void setRequestedStartAt(Instant v) { this.requestedStartAt = v; }
    public int getTrialDays() { return trialDays; }
    public void setTrialDays(int v) { this.trialDays = v; }
    public Instant getTrialEnd() { return trialEnd; }
    public void setTrialEnd(Instant v) { this.trialEnd = v; }
    public Instant getAnchor() { return anchor; }
    public void setAnchor(Instant v) { this.anchor = v; }
    public int getGeneration() { return generation; }
    public void setGeneration(int v) { this.generation = v; }
    public long getPeriodIndex() { return periodIndex; }
    public void setPeriodIndex(long v) { this.periodIndex = v; }
    public Instant getPeriodStart() { return periodStart; }
    public void setPeriodStart(Instant v) { this.periodStart = v; }
    public Instant getPeriodEnd() { return periodEnd; }
    public void setPeriodEnd(Instant v) { this.periodEnd = v; }
    public Instant getNextBillingAt() { return nextBillingAt; }
    public void setNextBillingAt(Instant v) { this.nextBillingAt = v; }
    public boolean isSetupFeeBilled() { return setupFeeBilled; }
    public void setSetupFeeBilled(boolean v) { this.setupFeeBilled = v; }
    public boolean isUpcomingNotified() { return upcomingNotified; }
    public boolean isCancelAtPeriodEnd() { return cancelAtPeriodEnd; }
    public void setCancelAtPeriodEnd(boolean v) { this.cancelAtPeriodEnd = v; }
    public String getCancelReason() { return cancelReason; }
    public Instant getCancelledAt() { return cancelledAt; }
    public Instant getPausedAt() { return pausedAt; }
    public Instant getResumeAt() { return resumeAt; }
    public String getPauseReason() { return pauseReason; }
    public Instant getClockAt() { return clockAt; }
    public String getCallbackUrl() { return callbackUrl; }
    public void setCallbackUrl(String v) { this.callbackUrl = v; }
    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> v) { this.metadata = v; }
    public String getActiveKey() { return activeKey; }
    public void setActiveKey(String v) { this.activeKey = v; }
    public long getVersion() { return version; }
    public void setVersion(long v) { this.version = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
