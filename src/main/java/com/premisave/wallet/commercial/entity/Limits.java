package com.premisave.wallet.commercial.entity;

import java.math.BigDecimal;

/** Optional ceilings in USD. A null value means "no limit set at this level". */
public class Limits {

    private BigDecimal singleTransactionUsd;
    private BigDecimal dailyVolumeUsd;
    private BigDecimal monthlyVolumeUsd;
    private Integer dailyPayoutCount;

    public Limits() {}

    public BigDecimal getSingleTransactionUsd() { return singleTransactionUsd; }
    public void setSingleTransactionUsd(BigDecimal v) { this.singleTransactionUsd = v; }
    public BigDecimal getDailyVolumeUsd() { return dailyVolumeUsd; }
    public void setDailyVolumeUsd(BigDecimal v) { this.dailyVolumeUsd = v; }
    public BigDecimal getMonthlyVolumeUsd() { return monthlyVolumeUsd; }
    public void setMonthlyVolumeUsd(BigDecimal v) { this.monthlyVolumeUsd = v; }
    public Integer getDailyPayoutCount() { return dailyPayoutCount; }
    public void setDailyPayoutCount(Integer v) { this.dailyPayoutCount = v; }

    /** True when this level sets nothing at all. */
    public boolean isEmpty() {
        return singleTransactionUsd == null && dailyVolumeUsd == null
                && monthlyVolumeUsd == null && dailyPayoutCount == null;
    }

    /** True when every value set here is within the ceiling set by {@code parent} (unset parent values impose nothing). */
    public boolean fitsWithin(Limits parent) {
        if (parent == null) return true;
        return within(singleTransactionUsd, parent.singleTransactionUsd)
                && within(dailyVolumeUsd, parent.dailyVolumeUsd)
                && within(monthlyVolumeUsd, parent.monthlyVolumeUsd)
                && (dailyPayoutCount == null || parent.dailyPayoutCount == null
                    || dailyPayoutCount <= parent.dailyPayoutCount);
    }

    private static boolean within(BigDecimal mine, BigDecimal ceiling) {
        return mine == null || ceiling == null || mine.compareTo(ceiling) <= 0;
    }

    /** The stricter of two limit sets, value by value. Either side may be null. */
    public static Limits stricter(Limits a, Limits b) {
        Limits out = new Limits();
        out.singleTransactionUsd = min(a == null ? null : a.singleTransactionUsd, b == null ? null : b.singleTransactionUsd);
        out.dailyVolumeUsd = min(a == null ? null : a.dailyVolumeUsd, b == null ? null : b.dailyVolumeUsd);
        out.monthlyVolumeUsd = min(a == null ? null : a.monthlyVolumeUsd, b == null ? null : b.monthlyVolumeUsd);
        Integer ca = a == null ? null : a.dailyPayoutCount;
        Integer cb = b == null ? null : b.dailyPayoutCount;
        if (ca == null) {
            out.dailyPayoutCount = cb;
        } else if (cb == null) {
            out.dailyPayoutCount = ca;
        } else {
            out.dailyPayoutCount = Math.min(ca, cb);
        }
        return out;
    }

    private static BigDecimal min(BigDecimal x, BigDecimal y) {
        if (x == null) return y;
        if (y == null) return x;
        return x.compareTo(y) <= 0 ? x : y;
    }
}
