/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.log.LogMask;

/**
 * The MLS operation retry schedule. Scheduled work is not deduplicated: convergence comes from
 * extra runs finding {@link MlsResultStatus#NO_OP}. Backoff is linear, not exponential. Three
 * nested bounds (WorkManager attempts, drain iterations, postprocess iterations) each terminate on
 * their own, since the failure a bound exists for is the one where an outer layer is not running.
 */
public final class MlsRetryPolicy {

    /** Outermost: total scheduled attempts per work item before WorkManager gives up. */
    public static final int MAX_ATTEMPTS_PER_ITEM = 1000;
    /** Middle: how many items one executor drain may process. */
    public static final int MAX_DRAIN_ITERATIONS = 100;
    /** Innermost: postprocess iterations per item; the same bound as the drive loop's cap. */
    public static final int MAX_POSTPROCESS_ITERATIONS = MlsDriveLoop.DEFAULT_MAX_ITERATIONS;

    /** One row per batch, asserted at execution. */
    public static final int MAX_ROWS_PER_BATCH = 1;

    /** The regular linear step once the immediate ladder is spent. */
    public static final long DEFAULT_RETRY_DELAY_MS = 300_000L;
    /**
     * The immediate ladder's fixed delay, also applied before every tranche including the first.
     */
    public static final long IMMEDIATE_RETRY_DELAY_MS = 30_000L;
    /** How many steps of the immediate ladder run before the regular delay takes over. */
    public static final int IMMEDIATE_RETRY_STEPS = 3;

    /**
     * The delay before {@code attempt}: 30 s for the first three, then
     * {@code DEFAULT_RETRY_DELAY_MS * (attempt - 3)}.
     *
     * @param attempt 1-based; below 1 is treated as the first
     */
    public static long retryDelayMs(final int attempt) {
        final int a = Math.max(1, attempt);
        if (a <= IMMEDIATE_RETRY_STEPS) return IMMEDIATE_RETRY_DELAY_MS;
        return DEFAULT_RETRY_DELAY_MS * (long) (a - IMMEDIATE_RETRY_STEPS);
    }

    /** Whether {@code attempt} is still inside the immediate ladder. */
    public static boolean isImmediateRetry(final int attempt) {
        return Math.max(1, attempt) <= IMMEDIATE_RETRY_STEPS;
    }

    /** Whether this item has exhausted the outermost bound. */
    public static boolean attemptsExhausted(final int attempt) {
        return Math.max(1, attempt) >= MAX_ATTEMPTS_PER_ITEM;
    }

    /** What a drain pass concluded; the only input to whether follow-up work is scheduled. */
    public enum Outcome {
        /** Re-run promptly; the one outcome that schedules follow-up work. */
        RETRY_IMMEDIATELY,
        /**
         * More retries are wanted, from the inline path. Schedules nothing: the inline trigger
         * fires again, and scheduling would stack a second driver.
         */
        NEED_MORE_RETRIES,
        /** Converged: a pass found nothing to do. */
        DONE,
        /** Terminal failure. */
        FAILED;
    }

    /** Only {@link Outcome#RETRY_IMMEDIATELY} schedules follow-up work. */
    public static boolean schedulesFollowUp(final Outcome outcome) {
        return outcome == Outcome.RETRY_IMMEDIATELY;
    }

    /**
     * Maps a drive-loop status onto a drain outcome. {@link MlsResultStatus#FAIL_RETRY} schedules a
     * retry from the queue and does nothing inline.
     */
    public static Outcome outcomeOf(final MlsResultStatus status, final boolean inline) {
        if (status == null) return Outcome.NEED_MORE_RETRIES;
        switch (status) {
            case NO_OP:
            case SUCCESS:
                return Outcome.DONE;
            case FAIL_NO_RETRY:
                return Outcome.FAILED;
            case PENDING:
            case FAIL_RETRY:
            default:
                return inline ? Outcome.NEED_MORE_RETRIES : Outcome.RETRY_IMMEDIATELY;
        }
    }

    /** One unit of scheduled work: a single {@code (group_id, msisdn)} pair, never a batch. */
    public static final class WorkItem {
        public final String groupId;
        public final String msisdn;
        /** 1-based attempt number, carried in the payload so it survives the process. */
        public final int immediateRetryAttempt;

        public WorkItem(final String groupId, final String msisdn,
                final int immediateRetryAttempt) {
            this.groupId = groupId == null ? "" : groupId;
            this.msisdn = msisdn == null ? "" : msisdn;
            this.immediateRetryAttempt = Math.max(1, immediateRetryAttempt);
        }

        public WorkItem next() {
            return new WorkItem(groupId, msisdn, immediateRetryAttempt + 1);
        }

        public long delayMs() { return retryDelayMs(immediateRetryAttempt); }

        /** Not a dedup key; two items for the same pair are two items. */
        @Override public String toString() {
            return "work{" + groupId + "/" + LogMask.number(msisdn)
                    + " attempt=" + immediateRetryAttempt
                    + " delay=" + delayMs() + "ms}";
        }
    }

    private MlsRetryPolicy() {}
}
