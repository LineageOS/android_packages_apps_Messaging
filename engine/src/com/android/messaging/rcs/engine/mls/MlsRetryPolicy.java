/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.messaging.rcs.engine.mls;

/**
 * The MLS operation retry schedule — bounds, backoff, and the two rules a reimplementer will get
 * wrong (rework item 5.6, §8.6, §19.3).
 *
 * <p>Pure, so the parts that are easy to get wrong and expensive to discover later are checkable on
 * the host. The WorkManager plumbing that carries these decisions is thin by comparison and lives in
 * the app layer.
 *
 * <h2>Two verified negatives, and both look like bugs until you know why</h2>
 *
 * <p><b>1. No deduplication of scheduled work.</b> The instinct is to key work uniquely per
 * conversation so a burst collapses into one run. Doing that would REMOVE the idempotence mechanism
 * rather than improve it: convergence comes from extra runs finding {@link MlsResultStatus#NO_OP}
 * (§8.6, §19.3-29). Google Messages' enqueue options object has every field null, and WorkManager
 * uniqueness there is keyed by worker TYPE, not by group. Duplicate runs are the design.
 *
 * <p><b>2. Linear backoff, not exponential.</b> Also deliberate (§19.3-32), on both paths.
 *
 * <p>Both patterns already in this tree contradict these. {@code MlsRefreshWorker} dedups by unique
 * name, and every backoff we have — WorkManager's default, the AIDL bind reconnect — is exponential.
 * Neither IS the MLS retry queue, so neither is a conflict in itself, but <b>reusing either as the
 * base for this drain would silently violate both verified negatives</b>, which is exactly the kind
 * of error that surfaces months later as "recovery sometimes doesn't".
 *
 * <h2>Three nested bounds, each terminating on its own</h2>
 *
 * <pre>
 *   WorkManager attempts  ≤ 1000   ⊃   drain iterations ≤ 100   ⊃   postprocess ≤ 10
 * </pre>
 *
 * <p>Nested, not alternative. The innermost is {@link MlsDriveLoop}'s cap, already built in Stage F —
 * so a runaway postprocess is caught by the drive loop, a runaway drain by this class, and a runaway
 * schedule by WorkManager. Each has to terminate without relying on the others, because the failure
 * that motivates a bound is precisely the one where an outer layer is not running.
 */
public final class MlsRetryPolicy {

    // -- the three nested bounds -------------------------------------------------------------------

    /** Outermost: total scheduled attempts per work item before WorkManager gives up. */
    public static final int MAX_ATTEMPTS_PER_ITEM = 1000;
    /** Middle: how many items one executor drain may process. */
    public static final int MAX_DRAIN_ITERATIONS = 100;
    /**
     * Innermost: postprocess iterations per item.
     *
     * <p>Deliberately equal to {@link MlsDriveLoop#DEFAULT_MAX_ITERATIONS} — they are the same
     * bound seen from two sides, and letting them drift would leave one of the two unreachable.
     */
    public static final int MAX_POSTPROCESS_ITERATIONS = MlsDriveLoop.DEFAULT_MAX_ITERATIONS;

    /** One row per batch, asserted at execution — the drain is not a bulk operation. */
    public static final int MAX_ROWS_PER_BATCH = 1;

    // -- the backoff ladder ------------------------------------------------------------------------

    /** The regular linear step once the immediate ladder is spent. */
    public static final long DEFAULT_RETRY_DELAY_MS = 300_000L;
    /** The immediate ladder's fixed delay, also applied BEFORE every tranche including the first. */
    public static final long IMMEDIATE_RETRY_DELAY_MS = 30_000L;
    /** How many steps of the immediate ladder run before the regular delay takes over. */
    public static final int IMMEDIATE_RETRY_STEPS = 3;

    /**
     * The delay before {@code attempt} (1-based).
     *
     * <p>A hard-coded 3-step immediate ladder, then LINEAR growth: {@code attempt <= 3 ? 30s :
     * defaultDelay * (attempt - 3)}. Linear because a transient failure that resolves in a minute
     * should not be waited out for an hour because it happened to fail four times — which is what
     * exponential does, and why §19.3-32 rules it out on both paths.
     *
     * @param attempt 1-based; anything below 1 is treated as the first attempt
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

    // -- the scheduling asymmetry ------------------------------------------------------------------

    /**
     * What a drain pass concluded, and the ONLY input to whether follow-up work is scheduled.
     */
    public enum Outcome {
        /** Re-run promptly. The one outcome that schedules follow-up work. */
        RETRY_IMMEDIATELY,
        /**
         * More retries are wanted, but this outcome came from the INLINE path.
         *
         * <p>Schedules NOTHING (§19.3-33). The asymmetry is deliberate and reads like a bug: the
         * inline path already runs on a trigger that will fire again, so scheduling here would stack
         * a second driver on top of the one that is already coming.
         */
        NEED_MORE_RETRIES,
        /** Converged — a pass found nothing to do. This is how convergence is DETECTED. */
        DONE,
        /** Terminal failure. Nothing to re-drive. */
        FAILED;
    }

    /**
     * Should this outcome schedule follow-up work?
     *
     * <p>Only {@link Outcome#RETRY_IMMEDIATELY}. Note in particular that
     * {@link Outcome#NEED_MORE_RETRIES} does NOT — see its doc for why that is not an oversight.
     */
    public static boolean schedulesFollowUp(final Outcome outcome) {
        return outcome == Outcome.RETRY_IMMEDIATELY;
    }

    /**
     * Map a drive-loop status onto a drain outcome.
     *
     * <p>{@code inline} carries the asymmetry: the same {@link MlsResultStatus#FAIL_RETRY} means
     * "schedule a retry" from the queue and "do nothing, the trigger is coming back" inline.
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

    /**
     * One unit of scheduled work: a single {@code (group_id, msisdn)} pair.
     *
     * <p>Deliberately NOT a batch. {@link #MAX_ROWS_PER_BATCH} is asserted at execution so that a
     * later "optimisation" that widens the unit fails loudly rather than quietly serialising
     * unrelated conversations behind each other.
     */
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

        /** The next attempt of the same item. */
        public WorkItem next() {
            return new WorkItem(groupId, msisdn, immediateRetryAttempt + 1);
        }

        /** The delay before running this item. */
        public long delayMs() { return retryDelayMs(immediateRetryAttempt); }

        /**
         * NOT a dedup key, and there is deliberately no method that returns one.
         *
         * <p>Two work items for the same pair are two items. If a future caller wants to collapse
         * them, that is the change §19.3-29 forbids — see the class doc.
         */
        @Override public String toString() {
            return "work{" + groupId + "/" + msisdn + " attempt=" + immediateRetryAttempt
                    + " delay=" + delayMs() + "ms}";
        }
    }

    private MlsRetryPolicy() {}
}
