package com.premisave.wallet.commercial.billing;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * When a failed invoice is tried again. With a schedule of [1, 3, 5] an invoice is tried up to four
 * times: when it falls due, then 1, 3 and 5 days after the due date. An outage at the provider is not
 * the customer's failure: it is tried again after a short delay and does not use up a retry, but only
 * a few times in a row so a permanent fault cannot loop forever.
 */
public final class RetryPolicy {

    public static final Duration TRANSIENT_DELAY = Duration.ofMinutes(15);
    public static final int MAX_FREE_TRANSIENT = 4;
    /** A retry is never set sooner than this after the failure that caused it, however late the scheduler ran. */
    public static final Duration MIN_GAP = Duration.ofHours(1);

    private RetryPolicy() {}

    /**
     * @param failedAttempts counted failures so far, including the one that just happened (at least 1)
     * @return when to try again, or null when the schedule is used up
     */
    public static Instant nextRetryAt(Instant dueAt, List<Integer> scheduleDays, int failedAttempts, Instant now) {
        if (failedAttempts < 1 || failedAttempts > scheduleDays.size()) return null;
        Instant scheduled = dueAt.plus(Duration.ofDays(scheduleDays.get(failedAttempts - 1)));
        Instant earliest = now.plus(MIN_GAP);
        return scheduled.isBefore(earliest) ? earliest : scheduled;
    }

    public static boolean isTransientCode(String code) {
        return "PROVIDER_ERROR".equals(code);
    }
}
