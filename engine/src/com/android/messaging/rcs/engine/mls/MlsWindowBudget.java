/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * A rolling-window allowance with a declared posture for an unreachable store and an unreadable
 * record. Pure: the adapter supplies the key, the raw record and an elapsed-realtime reading, then
 * performs {@link Decision#storeAction()}. An allowed charge always carries a write, unreadable is
 * never read as empty, and a posture with no arm is {@link Outcome#UNANSWERABLE}. See
 * docs/mls/budgets.md.
 */
public final class MlsWindowBudget {

    // ---- Postures.

    /**
     * What a budget does when no store at all is reachable (not an empty store, not a failed
     * write): a charge made now would be counted by nothing.
     */
    public enum OnNoStore {
        /** Refuse: an unbounded loop is worse than staying diverged one cycle longer. */
        REFUSE,
        /**
         * Decide again against the adapter's process-local store, for bounds whose refusal would
         * also block the way out (such as removing a wedged peer). Only for adapters that have such
         * a store.
         */
        DEGRADE_LOCAL,
        /** Proceed uncounted. Declared by no shipped budget; exists for tests to assert that. */
        ALLOW_UNCOUNTED,
    }

    /**
     * What a budget does with a record that is present and does not parse. Reading it as empty
     * would restore an allowance on a parse error; refusing forever would kill the conversation.
     */
    public enum OnUnreadable {
        /** Refuse this attempt and drop the record; the next attempt starts from empty. */
        REFUSE_AND_DISCARD,
    }

    // ---- Verdicts.

    /** What {@link #claim} concluded. */
    public enum Outcome {
        /**
         * Proceed only after {@link Decision#value()} is stored. Charged before the attempt, so an
         * operation that crashes still counts.
         */
        CHARGE_AND_ALLOW,
        /** Proceed with nothing counted. Reachable only from {@link OnNoStore#ALLOW_UNCOUNTED}. */
        ALLOW_UNCOUNTED,
        /** Decide again against the process-local store; not an allow. */
        DEGRADE_TO_LOCAL_STORE,
        /** Do not proceed. {@link Decision#reason()} says why. */
        REFUSE,
        /**
         * The policy cannot answer: a posture with no arm. Neither an allow nor a refusal, so the
         * gap stays visible.
         */
        UNANSWERABLE,
    }

    /** Why {@link #claim} concluded what it did; one per refusal line an adapter logs. */
    public enum Reason {
        NOTHING_REFUSED,
        /** No key: the operation cannot be bounded. */
        NO_KEY,
        /** No store is reachable. What follows is {@link #onNoStore()}. */
        NO_STORE,
        RECORD_UNREADABLE,
        /** The allowance is spent; a rate, so it refills (see {@link Decision#remainingMs}). */
        WINDOW_SPENT,
        /** {@link Outcome#UNANSWERABLE}: a posture value with no arm in {@link #claim}. */
        POSTURE_NOT_IMPLEMENTED,
    }

    /** What the adapter must do to its store as a result of this decision. */
    public enum StoreAction {
        NOTHING,
        WRITE,
        DISCARD,
    }

    /** Whether the adapter could reach a store at all. */
    public enum Store {
        /** A durable store, or an adapter's declared process-local fallback. */
        AVAILABLE,
        UNREACHABLE,
    }

    // ---- Decision.

    /**
     * The verdict, what the adapter owes the store, and the numbers for its log line (computed from
     * the same decode as the verdict).
     */
    public static final class Decision {

        private final Outcome mOutcome;
        private final Reason mReason;
        private final StoreAction mStoreAction;
        private final String mValue;
        private final int mCount;
        private final long mRemainingMs;
        private final boolean mAdoptedLegacyRecord;

        private Decision(final Outcome outcome, final Reason reason, final StoreAction storeAction,
                final String value, final int count, final long remainingMs,
                final boolean adoptedLegacyRecord) {
            mOutcome = outcome;
            mReason = reason;
            mStoreAction = storeAction;
            mValue = value;
            mCount = count;
            mRemainingMs = remainingMs;
            mAdoptedLegacyRecord = adoptedLegacyRecord;
        }

        public Outcome outcome() {
            return mOutcome;
        }

        public Reason reason() {
            return mReason;
        }

        public StoreAction storeAction() {
            return mStoreAction;
        }

        /** The record to store; non-null exactly when the action is {@link StoreAction#WRITE}. */
        public String value() {
            return mValue;
        }

        /** Charges already in this window, before this decision's own. */
        public int count() {
            return mCount;
        }

        /** Time until the allowance refills, or 0; a lower bound (see {@link MlsMonotonicAge}). */
        public long remainingMs() {
            return mRemainingMs;
        }

        /** Whether a legacy wall-clock record was adopted while deciding. */
        public boolean adoptedLegacyRecord() {
            return mAdoptedLegacyRecord;
        }

        /**
         * Whether the operation may go ahead; false for {@link Outcome#DEGRADE_TO_LOCAL_STORE},
         * which is an instruction to decide again.
         */
        public boolean mayProceed() {
            return mOutcome == Outcome.CHARGE_AND_ALLOW || mOutcome == Outcome.ALLOW_UNCOUNTED;
        }

        /** False only for {@link Outcome#UNANSWERABLE}: a defect, not a refusal. */
        public boolean answered() {
            return mOutcome != Outcome.UNANSWERABLE;
        }

        @Override
        public String toString() {
            return "Decision[" + mOutcome + " " + mReason + " " + mStoreAction
                    + (mValue == null ? "" : " " + mValue) + " count=" + mCount + "]";
        }
    }

    // ---- Shipped budgets.

    /** Rebuilds per window: enough to cover one that raced something, not enough to spin. */
    public static final int REBUILD_MAX_PER_WINDOW = 3;

    /** Long enough to throttle a spin to a trickle, short enough to heal. */
    public static final long REBUILD_WINDOW_MS = 6L * 60 * 60 * 1000;

    /**
     * How long one rebuild attempt suppresses further attempts on the same conversation, so one
     * burst of traffic cannot spend the whole window on one fault. Suppressed attempts are not
     * charged. The stamp is kept by {@code MlsPeerGuard}.
     */
    public static final long REBUILD_EPISODE_MS = 60_000L;

    /** RCC.16 §11.2.2's limit; a spec value, not a tuning knob. */
    public static final int EXTERNAL_COMMIT_MAX_PER_DAY = 50;

    /** RCC.16 §11.2.2's window: a day. */
    public static final long EXTERNAL_COMMIT_WINDOW_MS = 24L * 60 * 60 * 1000;


    /**
     * The rate bound on automatic conversation rebuilds. A rate, never a total: the window rolls,
     * so a transient fault cannot kill a conversation permanently.
     */
    public static final MlsWindowBudget REBUILD = new MlsWindowBudget(
            "MlsRebuildLimiter", REBUILD_MAX_PER_WINDOW, REBUILD_WINDOW_MS,
            OnNoStore.REFUSE, OnUnreadable.REFUSE_AND_DISCARD);

    /**
     * RCC.16 §11.2.2: at most 50 external commits per group per day. A separate allowance from
     * {@link #REBUILD}, kept in a separate file under separate keys, and independent of the era
     * advancement quota.
     */
    public static final MlsWindowBudget EXTERNAL_COMMIT = new MlsWindowBudget(
            "MlsExternalCommitBudget", EXTERNAL_COMMIT_MAX_PER_DAY, EXTERNAL_COMMIT_WINDOW_MS,
            OnNoStore.REFUSE, OnUnreadable.REFUSE_AND_DISCARD);

    // ---- Policy.

    private final String mName;
    private final int mMaxPerWindow;
    private final long mWindowMs;
    private final OnNoStore mOnNoStore;
    private final OnUnreadable mOnUnreadable;

    /** Public so tests can build a budget for every {@link OnNoStore} value. */
    public MlsWindowBudget(final String name, final int maxPerWindow, final long windowMs,
            final OnNoStore onNoStore, final OnUnreadable onUnreadable) {
        mName = name;
        mMaxPerWindow = maxPerWindow;
        mWindowMs = windowMs;
        mOnNoStore = onNoStore;
        mOnUnreadable = onUnreadable;
    }

    /** The owning adapter's simple name, for its log lines. */
    public String name() {
        return mName;
    }

    public int maxPerWindow() {
        return mMaxPerWindow;
    }

    public long windowMs() {
        return mWindowMs;
    }

    public OnNoStore onNoStore() {
        return mOnNoStore;
    }

    public OnUnreadable onUnreadable() {
        return mOnUnreadable;
    }

    /**
     * Whether one more operation may proceed against {@code key} now, and what the store owes.
     *
     * @param key the store key; {@code null} or empty refuses with {@link Reason#NO_KEY}
     * @param store whether any store is reachable; {@link Store#UNREACHABLE} applies
     *     {@link #onNoStore()}
     * @param raw the stored record, or {@code null} for nothing stored
     * @param nowElapsedMs {@code SystemClock.elapsedRealtime()}, never the wall clock
     */
    public Decision claim(final String key, final Store store, final String raw,
            final long nowElapsedMs) {
        if (key == null || key.isEmpty()) {
            // A budget that cannot be looked up cannot be said to allow anything.
            return new Decision(Outcome.REFUSE, Reason.NO_KEY, StoreAction.NOTHING, null,
                    0, mWindowMs, false);
        }
        if (store != Store.AVAILABLE) {
            switch (mOnNoStore) {
                case REFUSE:
                    return new Decision(Outcome.REFUSE, Reason.NO_STORE, StoreAction.NOTHING, null,
                            0, mWindowMs, false);
                case DEGRADE_LOCAL:
                    return new Decision(Outcome.DEGRADE_TO_LOCAL_STORE, Reason.NO_STORE,
                            StoreAction.NOTHING, null, 0, mWindowMs, false);
                case ALLOW_UNCOUNTED:
                    return new Decision(Outcome.ALLOW_UNCOUNTED, Reason.NO_STORE,
                            StoreAction.NOTHING, null, 0, mWindowMs, false);
                default:
                    // A posture with no arm: visible as unanswered, neither refused nor allowed.
                    return new Decision(Outcome.UNANSWERABLE, Reason.POSTURE_NOT_IMPLEMENTED,
                            StoreAction.NOTHING, null, 0, mWindowMs, false);
            }
        }
        final MlsRebuildWindowRecord stored = MlsRebuildWindowRecord.decode(raw);
        if (stored == null) {
            // Refuse once and discard, so a format skew costs one deferred operation per key.
            return new Decision(Outcome.REFUSE, Reason.RECORD_UNREADABLE, StoreAction.DISCARD, null,
                    -1, mWindowMs, false);
        }
        // Roll first, or the count accumulates into a permanent total. Rolling also adopts a legacy
        // wall-clock record (see MlsRebuildWindowRecord).
        final MlsRebuildWindowRecord rec = stored.rolled(mWindowMs, nowElapsedMs);
        final boolean adopted = stored.isLegacyWallClock();
        if (rec.spent(mMaxPerWindow)) {
            // Persist an adoption even when refusing, or a spent legacy record is re-adopted on
            // every attempt and its window never expires.
            return new Decision(Outcome.REFUSE, Reason.WINDOW_SPENT,
                    adopted ? StoreAction.WRITE : StoreAction.NOTHING,
                    adopted ? rec.encode() : null,
                    rec.count(), rec.remainingMs(mWindowMs, nowElapsedMs), adopted);
        }
        return new Decision(Outcome.CHARGE_AND_ALLOW, Reason.NOTHING_REFUSED, StoreAction.WRITE,
                rec.charged().encode(), rec.count(), rec.remainingMs(mWindowMs, nowElapsedMs),
                adopted);
    }

    /**
     * Charges in the current window, for diagnostics; never writes. {@code -1} for an unreadable
     * record, never 0.
     */
    public int countIn(final String raw, final long nowElapsedMs) {
        final MlsRebuildWindowRecord rec = MlsRebuildWindowRecord.decode(raw);
        if (rec == null) return -1;
        return rec.rolled(mWindowMs, nowElapsedMs).count();
    }

    @Override
    public String toString() {
        return "MlsWindowBudget[" + mName + " " + mMaxPerWindow + "/" + mWindowMs + "ms "
                + mOnNoStore + " " + mOnUnreadable + "]";
    }
}
