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
 * <b>A rolling-window allowance, and what it does when it cannot read its own record.</b>
 *
 * <h2>The problem this class exists to solve</h2>
 *
 * <p>{@code MlsRebuildLimiter} and {@code MlsExternalCommitBudget} are the SAME SHAPE over
 * {@link MlsRebuildWindowRecord} with different constants: decode, roll, refuse if spent, otherwise
 * charge and store. That shape has already been found twice — the wall-clock refill was fixed
 * in the limiter and the identical defect then turned up, one window-length larger, in the budget, by
 * sweeping rather than by report. Two copies of one decision is how one of them is fixed and the
 * other is not.
 *
 * <p>What was genuinely different between them was never the arithmetic. It was <b>the posture</b>:
 * what each does when it cannot reach a store at all, and what each does with a record that is there
 * and will not parse. The plan's §3.2 put the three answers side by side for the first time:
 *
 * <table>
 *   <caption>The posture table — D2, and the whole reason this class carries the posture as a field</caption>
 *   <tr><th>class</th><th>no store reachable</th><th>record present, unparseable</th></tr>
 *   <tr><td>{@code MlsRebuildLimiter}</td><td>{@link OnNoStore#REFUSE}</td>
 *       <td>{@link OnUnreadable#REFUSE_AND_DISCARD}</td></tr>
 *   <tr><td>{@code MlsExternalCommitBudget}</td><td>{@link OnNoStore#REFUSE} — <b>was
 *       {@link OnNoStore#ALLOW_UNCOUNTED} until D2's flip; the argument is at that class</b></td>
 *       <td>{@link OnUnreadable#REFUSE_AND_DISCARD}</td></tr>
 *   <tr><td>{@code MlsPeerGuard}</td><td>{@link OnNoStore#DEGRADE_LOCAL}</td>
 *       <td>{@link OnUnreadable#REFUSE_AND_DISCARD}</td></tr>
 * </table>
 *
 * <p>All three were individually defensible and each javadoc argued its case well. What was wrong is
 * that the divergence was <b>invisible</b>: nothing stated the three answers together, nothing tested
 * them, and one of them was a hole that read as a log line. D2 (a) makes the posture a typed field,
 * so the divergence is a value a test can assert about rather than a paragraph a reader must find.
 *
 * <p>{@code MlsPeerGuard} is in the table and is not an instance of this class — it is not a rolling
 * window, it is two records under one file. Its row is here because <b>the posture vocabulary is the
 * deliverable</b>, not the base class; {@code MlsGuardPersistenceTest} pins its declared row
 * structurally, and {@link OnNoStore#DEGRADE_LOCAL} exists in this enum so that the third answer has
 * a name in the same alphabet as the other two.
 *
 * <h2>What is here and what stays in the adapter</h2>
 *
 * <p>Here: the numbers, the posture, the codec choice, the roll, the spent test, the charge, and
 * <b>what the store must be told to do about it</b>. The adapter reads a string, calls
 * {@link #claim}, performs {@link Decision#storeAction()}, and logs. It owns no policy.
 *
 * <p>Not here, deliberately: the KEY. A key is derived from a conversation and the two adapters
 * derive theirs identically ({@code canonicalKey}), but deriving it needs nothing from a budget and
 * a policy that took a group id would invite a second copy of that derivation. {@link #claim} takes
 * the key only to answer {@link Reason#NO_KEY}, which is a decision — <em>we cannot say an operation
 * is within a budget we cannot look up</em> — and never to build one.
 *
 * <h2>Three shapes this class refuses to have</h2>
 *
 * <ol>
 *   <li><b>An allowed charge that was not recorded is unrepresentable.</b>
 *       {@link Outcome#CHARGE_AND_ALLOW} always carries {@link StoreAction#WRITE} and a value, so an
 *       adapter cannot fall through into a proceeding path that skips the write. That is
 *       the uncharged-advance defect closed in the type, the same move {@link MlsStateChangeGate} makes for the
 *       era advance.</li>
 *   <li><b>Unreadable does not share a value with absent.</b> {@link MlsRebuildWindowRecord#decode}
 *       already answers {@link MlsRebuildWindowRecord#EMPTY} for "nothing stored" and {@code null}
 *       for "stored and unparseable"; this class keeps the two apart all the way to
 *       {@link Reason#RECORD_UNREADABLE}, and {@link #countIn} answers {@code -1} rather than 0 for
 *       the second. A reader that refuses a case must not spell it the same way as "nothing
 *       recorded".</li>
 *   <li><b>A posture with no arm is not a posture.</b> {@link #claim} switches on every
 *       {@link OnNoStore} value, and a value added without an arm reaches
 *       {@link Outcome#UNANSWERABLE} rather than any proceeding path.
 *       {@code MlsWindowBudgetTest.everyDeclaredPostureBehavesAsItIsDeclared} enumerates from
 *       {@code values()}, so the arm cannot be forgotten quietly.</li>
 * </ol>
 *
 * <h2>The clock</h2>
 *
 * <p>Every stamp is {@code SystemClock.elapsedRealtime()} and every age comes from
 * {@link MlsMonotonicAge}, whose every branch answers <b>the largest age it can prove</b>. A clock
 * move can retain a spent window and can never refill one. The reasoning, the reboot price and the
 * legacy-record adoption all live in {@link MlsRebuildWindowRecord}; this class only decides.
 */
public final class MlsWindowBudget {

    // ---- the posture vocabulary ----------------------------------------------------------------

    /**
     * What a budget does when <b>no store of any kind is reachable</b> — decision D2.
     *
     * <p>"No store" is not "an empty store" and it is not "a write that failed". It is the state in
     * which the adapter has nowhere to read a record from and nowhere to put one, so a charge made
     * now would not be counted by anything.
     */
    public enum OnNoStore {
        /**
         * Refuse the operation.
         *
         * <p>{@code MlsRebuildLimiter}'s stated argument, and since D2's flip also
         * {@code MlsExternalCommitBudget}'s: <em>an unbounded loop is worse than a conversation
         * that stays diverged one cycle longer.</em> Right for a bound whose failure mode is the
         * thing the bound exists to prevent.
         */
        REFUSE,
        /**
         * Decide again against a process-local store, and say so once.
         *
         * <p>{@code MlsPeerGuard}'s answer, and it is right for it: refusing every state change also
         * refuses the REMOVE that gets a wedged peer out of a group, which was the first thing
         * the original remediation reached for. It is available only to an adapter that HAS a
         * process-local fallback; declaring it without building one would be a posture that lies,
         * which is why {@link Outcome#DEGRADE_TO_LOCAL_STORE} is a directive to the adapter and not
         * an allow.
         */
        DEGRADE_LOCAL,
        /**
         * Proceed, and count nothing.
         *
         * <p><b>Nothing declares this any more.</b> It was {@code MlsExternalCommitBudget}'s
         * answer, on the argument that "refusing here would make an unwritable prefs file silently
         * disable recovery, which is a worse failure than an unbounded count" — and the plan's §3.2
         * named it correctly as "a hole that reads as a log line": the one path in a class written
         * to protect a peer that reaches the unbounded state the class exists to prevent. D2 flipped
         * it to {@link #REFUSE} once the operator lever existed.
         *
         * <p>Kept in the enum rather than deleted, because a posture that cannot be NAMED cannot be
         * refused: {@code MlsWindowBudgetTest.noShippedBudgetProceedsUncounted} asserts that no
         * budget declares it, which is an assertion this value has to exist to carry.
         */
        ALLOW_UNCOUNTED,
    }

    /**
     * What a budget does with a record that <b>is</b> there and does not parse.
     *
     * <p>One value, and that is the finding rather than an oversight: all three classes already
     * agreed here, and they agreed for a reason worth keeping as the only option — reading a record
     * we cannot parse as "nothing spent" restores an allowance on the strength of a parse error, and
     * refusing forever converts a format skew into a permanently dead conversation. Refuse once,
     * loudly, then discard, so the skew costs one deferred operation per key.
     */
    public enum OnUnreadable {
        /** Refuse this attempt and drop the record; the next attempt is judged on an empty one. */
        REFUSE_AND_DISCARD,
    }

    // ---- the verdict vocabulary ----------------------------------------------------------------

    /** What {@link #claim} concluded. */
    public enum Outcome {
        /**
         * Proceed, <b>and only after</b> {@link Decision#value()} is on the store.
         *
         * <p>Charged BEFORE the attempt, the same discipline the two adapters already had: an
         * operation that CRASHES must still count, or a reproducible crash becomes an unthrottled
         * loop — the one failure mode a limiter exists to prevent and the one charging-on-success
         * leaves wide open.
         */
        CHARGE_AND_ALLOW,
        /** Proceed with nothing counted. Reachable only from {@link OnNoStore#ALLOW_UNCOUNTED}. */
        ALLOW_UNCOUNTED,
        /**
         * <b>Ask again against the adapter's process-local store.</b> Not an allow: an adapter that
         * treated this as one would have implemented {@link OnNoStore#ALLOW_UNCOUNTED} while
         * declaring {@link OnNoStore#DEGRADE_LOCAL}, which is the posture lying about itself.
         */
        DEGRADE_TO_LOCAL_STORE,
        /** Do not proceed. {@link Decision#reason()} says why. */
        REFUSE,
        /**
         * <b>The policy cannot answer from these facts.</b> A posture with no arm lands here rather
         * than falling into a proceeding path or a refusal — a budget that answered REFUSE for a
         * posture nobody implemented would hide the missing arm behind correct-looking behaviour
         * until the day it was supposed to allow something. {@link MlsStateChangeGate.Outcome} makes
         * the same move for a tier with no row.
         */
        UNANSWERABLE,
    }

    /** Why {@link #claim} concluded what it did. One value per refusal line an adapter emits. */
    public enum Reason {
        /** Nothing refused. */
        NOTHING_REFUSED,
        /**
         * No key was supplied, so there is nothing to count against and the operation cannot be
         * bounded at all. {@link MlsStateChangeGate.Reason#NO_BUDGET_KEY}'s row, one layer down.
         */
        NO_KEY,
        /** No store is reachable. What follows is {@link #onNoStore()}. */
        NO_STORE,
        /** A record exists under this key and did not parse. */
        RECORD_UNREADABLE,
        /** The window's allowance is spent. A RATE, so it refills; see {@link Decision#remainingMs}. */
        WINDOW_SPENT,
        /** {@link Outcome#UNANSWERABLE}: a posture value with no arm in {@link #claim}. */
        POSTURE_NOT_IMPLEMENTED,
    }

    /** What the adapter must do to its store as a result of this decision. */
    public enum StoreAction {
        /** Leave the store alone. */
        NOTHING,
        /** Write {@link Decision#value()} under the key. */
        WRITE,
        /** Remove the key. */
        DISCARD,
    }

    /** Whether the adapter could reach a store at all. */
    public enum Store {
        /** A store is reachable — durable, or an adapter's declared process-local fallback. */
        AVAILABLE,
        /** No store of any kind. {@link #onNoStore()} decides what happens. */
        UNREACHABLE,
    }

    // ---- the decision --------------------------------------------------------------------------

    /**
     * What the policy decided, what the adapter owes the store, and the two numbers its log line
     * needs.
     *
     * <p>The numbers are here rather than left for the adapter to recompute because recomputing them
     * means decoding the record a second time, and a log line derived from a second decode can
     * disagree with the decision it is describing.
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

        /** The record to store, non-null exactly when {@link #storeAction()} is {@link StoreAction#WRITE}. */
        public String value() {
            return mValue;
        }

        /** Charges already in this window, BEFORE this decision's own. */
        public int count() {
            return mCount;
        }

        /** How long until the allowance refills, or 0. A lower bound; see {@link MlsMonotonicAge}. */
        public long remainingMs() {
            return mRemainingMs;
        }

        /** Whether a legacy wall-clock record was adopted while deciding. */
        public boolean adoptedLegacyRecord() {
            return mAdoptedLegacyRecord;
        }

        /**
         * Whether the operation may go ahead.
         *
         * <p>{@link Outcome#DEGRADE_TO_LOCAL_STORE} may NOT: it is an instruction to decide again,
         * and an adapter that read it as an allow would have skipped the very store the posture
         * promised. {@link Outcome#UNANSWERABLE} may not either.
         */
        public boolean mayProceed() {
            return mOutcome == Outcome.CHARGE_AND_ALLOW || mOutcome == Outcome.ALLOW_UNCOUNTED;
        }

        /** Whether the policy reached a decision at all — false is a defect, not a refusal. */
        public boolean answered() {
            return mOutcome != Outcome.UNANSWERABLE;
        }

        @Override
        public String toString() {
            return "Decision[" + mOutcome + " " + mReason + " " + mStoreAction
                    + (mValue == null ? "" : " " + mValue) + " count=" + mCount + "]";
        }
    }

    // ---- the shipped budgets -------------------------------------------------------------------

    /**
     * Rebuilds allowed per window. Three is enough to cover "the first one raced something" without
     * letting a genuinely unrepairable conversation spin.
     *
     * <p>Declared here rather than in {@code MlsRebuildLimiter} since Stage 3: a threshold belongs
     * beside the record that counts it and the policy that compares it, not in the store adapter
     * that neither counts nor compares. DoD-3's inventory follows it.
     */
    public static final int REBUILD_MAX_PER_WINDOW = 3;

    /** The rebuild window. Long enough that a spin is throttled to a trickle, short enough to heal. */
    public static final long REBUILD_WINDOW_MS = 6L * 60 * 60 * 1000;

    /**
     * How long one rebuild ATTEMPT suppresses further attempts on the same conversation.
     *
     * <h2>Episode suppression is not the rate limit, and the difference matters</h2>
     *
     * <p>{@link #REBUILD_MAX_PER_WINDOW} over {@link #REBUILD_WINDOW_MS} bounds how often a
     * conversation may be rebuilt over HOURS, and it is persisted so a crash loop cannot buy
     * attempts. This is a different and much shorter guard: it stops a single BURST of traffic from
     * spending that entire allowance on ONE fault.
     *
     * <p>Device-observed 2026-08-10, twice. Six group sends ~6s apart each ran the ladder
     * independently — self-heal, era advance, refused, fallback, rebuild — and three claims landed
     * within ten seconds, exhausting a six-hour allowance on three attempts at an identical
     * operation that could only fail identically. The same thing then blocked a 1:1 outright: the
     * 80-message stress burst burned the allowance, so when the pair turned out to be genuinely
     * diverged (era 7 vs the server's 6) the one repair that could fix it was throttled for six
     * hours by its own earlier attempts.
     *
     * <p>A suppressed attempt does NOT charge the persistent allowance — that is the whole point.
     * Nothing has changed since the attempt seconds ago, so this is not a repair being declined; it
     * is the same repair not being started twice.
     *
     * <p>Declared here for the reason
     * {@link #REBUILD_MAX_PER_WINDOW}'s javadoc already gives: the two bounds are read together and
     * only make sense read together. The STAMP stays in {@code MlsPeerGuard}, which needs a
     * {@code Context} — a window length is arithmetic, a durable stamp is storage.
     */
    public static final long REBUILD_EPISODE_MS = 60_000L;

    /**
     * §11.2.2's number. <b>Not a tuning knob</b> — changing it diverges from the spec, and no value
     * of it can "match" Google Messages, which implements no budget at all.
     */
    public static final int EXTERNAL_COMMIT_MAX_PER_DAY = 50;

    /** §11.2.2's window: a day. */
    public static final long EXTERNAL_COMMIT_WINDOW_MS = 24L * 60 * 60 * 1000;


    /**
     * The rate bound on AUTOMATIC conversation rebuilds.
     *
     * <p>Three per six hours per conversation. Three is enough to cover "the first one raced
     * something" without letting a genuinely unrepairable conversation spin; the window is long
     * enough that a spin is throttled to a trickle and short enough to still heal.
     *
     * <p><b>A rate, never a total.</b> The window ROLLS: once it has elapsed the count is discarded
     * and a fresh window opens at the next attempt, so a conversation that needed three rebuilds
     * yesterday starts today with its full allowance. Every budget in this codebase used to bound
     * a TOTAL, which converts a transient fault into a permanently dead
     * conversation — strictly worse than retrying slowly forever, because the fault may clear on its
     * own and a spent total leaves nothing to notice it.
     *
     * <p>Posture {@link OnNoStore#REFUSE}: this bound has no other bound to fall back on, and an
     * unbounded rebuild loop is worse than a conversation that stays diverged one cycle longer.
     */
    public static final MlsWindowBudget REBUILD = new MlsWindowBudget(
            "MlsRebuildLimiter", REBUILD_MAX_PER_WINDOW, REBUILD_WINDOW_MS,
            OnNoStore.REFUSE, OnUnreadable.REFUSE_AND_DISCARD);

    /**
     * RCC.16 v4.0 §11.2.2: <em>"the client shall not attempt more than 50 External Commits per group
     * per day."</em>
     *
     * <p>Not {@link #REBUILD} with different numbers — a <b>different allowance from a different
     * authority</b>, and the two must never share a counter: the whole reason the external commit is
     * worth having is that its budget is independent of the era-advancement quota that strands a
     * group in the first place. Sharing the RECORD is not sharing the COUNTER;
     * the two adapters keep separate preference files under separate keys.
     *
     * <p>Kept as a <b>deliberate divergence from Google Messages</b>, which implements §11.2.2/§11.2.3 not at
     * all (a cross-boundary negative on two independent artifacts). The reasoning
     * is at {@code MlsExternalCommitBudget}; the number is not a tuning knob and no value of it can
     * "match" a client that has no budget, so if behavioural parity ever becomes the goal the class
     * is deleted rather than tuned.
     *
     * <p>Posture {@link OnNoStore#REFUSE} <b>since D2's flip</b>, which was taken on its own review
     * because refusing a recovery rung has a real cost. The full argument is at
     * {@code MlsExternalCommitBudget}; the one sentence is that a bound argued for on the grounds
     * that it protects a peer who cannot ask us to stop must not have an arm whose effect is no
     * bound at all.
     */
    public static final MlsWindowBudget EXTERNAL_COMMIT = new MlsWindowBudget(
            "MlsExternalCommitBudget", EXTERNAL_COMMIT_MAX_PER_DAY, EXTERNAL_COMMIT_WINDOW_MS,
            OnNoStore.REFUSE, OnUnreadable.REFUSE_AND_DISCARD);

    // ---- the policy ----------------------------------------------------------------------------

    private final String mName;
    private final int mMaxPerWindow;
    private final long mWindowMs;
    private final OnNoStore mOnNoStore;
    private final OnUnreadable mOnUnreadable;

    /**
     * Visible for the posture law's test, which must be able to build a budget for EVERY
     * {@link OnNoStore} value including the ones nothing ships. A posture arm that only the shipped
     * instances could reach would be a law asserted about two of three values.
     */
    public MlsWindowBudget(final String name, final int maxPerWindow, final long windowMs,
            final OnNoStore onNoStore, final OnUnreadable onUnreadable) {
        mName = name;
        mMaxPerWindow = maxPerWindow;
        mWindowMs = windowMs;
        mOnNoStore = onNoStore;
        mOnUnreadable = onUnreadable;
    }

    /** The owning adapter's simple name, for the adapter's own log lines. */
    public String name() {
        return mName;
    }

    public int maxPerWindow() {
        return mMaxPerWindow;
    }

    public long windowMs() {
        return mWindowMs;
    }

    /** This budget's declared answer to "no store is reachable". */
    public OnNoStore onNoStore() {
        return mOnNoStore;
    }

    /** This budget's declared answer to "the record is there and will not parse". */
    public OnUnreadable onUnreadable() {
        return mOnUnreadable;
    }

    /**
     * <b>May one more operation proceed against {@code key} now — and what does the store owe?</b>
     *
     * <p>Pure: no clock, no store, no system property, no log. The adapter supplies the reading and
     * the raw record and performs {@link Decision#storeAction()}.
     *
     * @param key the store key; {@code null} or empty refuses with {@link Reason#NO_KEY}
     * @param store whether a store is reachable at all — {@link Store#UNREACHABLE} routes to
     *     {@link #onNoStore()}
     * @param raw what the store holds under {@code key}, or {@code null} for nothing stored
     * @param nowElapsedMs {@code SystemClock.elapsedRealtime()}, never the wall clock
     */
    public Decision claim(final String key, final Store store, final String raw,
            final long nowElapsedMs) {
        if (key == null || key.isEmpty()) {
            // NO KEY MEANS NO BOUND, and it is also the only honest answer available: we cannot say
            // this operation is within a budget we could not look up. Both adapters already refused
            // a null key; this states why.
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
                    // A posture added to the enum and not to this switch. NOT a refusal and not an
                    // allow: an unimplemented arm must be visible as one.
                    return new Decision(Outcome.UNANSWERABLE, Reason.POSTURE_NOT_IMPLEMENTED,
                            StoreAction.NOTHING, null, 0, mWindowMs, false);
            }
        }
        final MlsRebuildWindowRecord stored = MlsRebuildWindowRecord.decode(raw);
        if (stored == null) {
            // UNREADABLE IS NOT UNCHARGED, and it is not "no window" either. Refuse once and
            // discard, so a format skew costs one deferred operation per key rather than a
            // permanent block or a silently restored allowance.
            return new Decision(Outcome.REFUSE, Reason.RECORD_UNREADABLE, StoreAction.DISCARD, null,
                    -1, mWindowMs, false);
        }
        // ROLL FIRST. Without this the count accumulates across windows and the rate arm silently
        // becomes the permanent total these budgets exist to avoid — the same bug that was found one
        // level down in the self-heal budget. rolled() also ADOPTS a legacy wall-clock record,
        // keeping its count and opening a fresh window; MlsRebuildWindowRecord carries why that
        // direction (over-retention) is the only safe one on an upgrade.
        final MlsRebuildWindowRecord rec = stored.rolled(mWindowMs, nowElapsedMs);
        final boolean adopted = stored.isLegacyWallClock();
        if (rec.spent(mMaxPerWindow)) {
            // PERSIST THE ADOPTION EVEN THOUGH WE ARE REFUSING. A legacy record already at its limit
            // would otherwise be re-adopted on every attempt, restarting its window each time — a
            // throttle that never expires, which is the permanent total in a different disguise.
            // This is the one refusal arm that writes, and it was a real trap rather than a
            // hypothetical: the refusal arm is the one that does not otherwise write.
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
     * How many charges {@code raw} records in the CURRENT window.
     *
     * <p>Diagnostics only, and it never writes: a diagnostic must not be able to change the thing it
     * reports on. Answers <b>-1</b> for a record it cannot read rather than 0 — a reader that
     * refuses a case must not spell it the same way as "nothing recorded", or the one number a
     * person would use to check the bound reports a full allowance for a corrupt file.
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
