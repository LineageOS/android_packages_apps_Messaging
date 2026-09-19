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
package com.android.messaging.rcs.e2ee;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import com.android.messaging.rcs.engine.mls.MlsLog;
import com.android.messaging.rcs.engine.mls.MlsWindowBudget;
import com.android.messaging.util.LogUtil;

/**
 * The rate bound on AUTOMATIC conversation rebuilds.
 *
 * <h2>This class is a STORE, not a policy</h2>
 *
 * <p>The numbers, the posture, the roll, the spent test and the charge all live in
 * {@link MlsWindowBudget#REBUILD}, which is pure Java on the host classpath and host-tested. What is
 * left here is the {@code SharedPreferences} adapter and the refusal lines: read a string, ask the
 * policy, do what {@link MlsWindowBudget.Decision#storeAction()} says, log. The
 * storage decisions — its own preferences file, {@code commit()} not {@code apply()} — are untouched;
 * it is the STORAGE that is host-side and the DECISION that should not be.
 *
 * <p>Why that mattered enough to do: this class and {@code MlsExternalCommitBudget} were the same
 * shape with different constants, and the SAME wall-clock refill turned up in both — in
 * the second one by sweeping, not by report. Two copies of one decision is how one of them gets fixed
 * and the other does not.
 *
 * <h2>A rate, never a total</h2>
 *
 * <p>This is the distinction the P0 turns on, and it is stated at {@link MlsWindowBudget#REBUILD}
 * along with the window that carries it. Every budget in this codebase before it bounded a TOTAL,
 * which converts a transient fault into a permanently dead conversation; the only thing bounded here
 * is how OFTEN we may rebuild, and there is no terminal state.
 *
 * <h2>The clock is {@code elapsedRealtime}, not the wall clock</h2>
 *
 * <p>This window used to be aged by {@code System.currentTimeMillis()}, so any jump forward large
 * enough rolled it and handed back the full allowance with nothing in the log to say time had not
 * passed. Stamps are now {@code SystemClock.elapsedRealtime()} and every age comes from
 * {@code MlsMonotonicAge}, which answers the largest age it can PROVE — so a clock move can retain a
 * spent window and can never refill one. {@code MlsRebuildWindowRecord} carries the whole decision,
 * including the price it charges after a reboot and the four reasons that price is the right trade
 * for THIS limiter, whose window is six hours rather than G2's one.
 *
 * <h2>Why this is NOT an {@link MlsPerConversationState}</h2>
 *
 * <p>Every other per-conversation store implements that interface so teardown clears it. This one
 * MUST NOT, and the reason is not an oversight to be tidied up later: <b>a rebuild's first act is to
 * forget the conversation</b>. If teardown cleared this counter, every rebuild would erase its own
 * evidence that it had happened, the rate bound would read zero on the next attempt, and a
 * conversation that cannot be repaired by rebuilding would rebuild in a tight loop forever —
 * claiming a peer key package and forcing a re-Welcome each time round. The counter has to outlive
 * exactly the state the operation it bounds destroys.
 *
 * <p>Consequently it is keyed by the CANONICAL CONVERSATION KEY and lives in its own preferences
 * file, untouched by {@code perConversationStores()}. The only things that clear it are the rolling
 * window and an explicit operator reset.
 *
 * <h2>What this is NOT the bound on — read before treating it as the peer's protection</h2>
 *
 * <p>It bounds OUR side: local state destroyed, KeyPackages claimed, work redone. It is <b>not</b>
 * the bound on what the PEER pays. A rebuild that re-creates a conversation the server already holds
 * makes every member re-join by Welcome, which is the identical cost an era advance imposes, and
 * that cost is counted by <b>one</b> budget — {@code MlsPeerGuard.allowEraAdvance} (G2).
 * This class alone allowed twelve re-creations a day (3 per 6h) while G2, written
 * because ~17 of them wedged a real person's phone, counted none of them and read "within budget"
 * the whole time. Two limiters accounting for one peer-facing cost separately is how that total
 * stays plausible, so {@code rebuildConversation} charges G2 as well and a refusal there stops it.
 *
 * <p>The two are kept apart rather than merged because they answer different questions and need
 * different lifetimes: G2 is per-group and this one is per-conversation-key, and a rebuild that is a
 * first create — nothing server-side, nobody re-joining — is bounded here and correctly charged
 * nothing there. They at least agree about time now; two bounds on one operation
 * trusting different clocks is the same accounting split that let seventeen re-creations stay
 * plausible while both read "within budget".
 */
public final class MlsRebuildLimiter {
    private static final String TAG = MlsLog.TAG;
    private static final String PREFS = "mls_rebuild_limiter";

    private final Context mCtx;
    private final MlsWindowBudget mPolicy;

    public MlsRebuildLimiter(final Context ctx) {
        this(ctx, MlsWindowBudget.REBUILD);
    }

    /**
     * The seam a test would use. It takes a POLICY rather than loose numbers on purpose: a
     * constructor taking {@code (maxPerWindow, windowMs)} would let a caller build a budget with no
     * declared posture, which is the field decision D2 exists to make visible.
     */
    public MlsRebuildLimiter(final Context ctx, final MlsWindowBudget policy) {
        mCtx = ctx == null ? null : ctx.getApplicationContext();
        mPolicy = policy;
    }

    private SharedPreferences prefs() {
        return mCtx == null ? null : mCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * May we rebuild {@code key} now — and if so, charge for it.
     *
     * <p><b>Charged BEFORE the attempt</b>, the same discipline as
     * {@link MlsReupgradeStore#claimReupgradeAttempt}: a rebuild that CRASHES must still count, or a
     * reproducible crash becomes an unthrottled loop. That is the one failure mode a limiter exists
     * to prevent and the one that charging-on-success would leave wide open. The policy makes it
     * unrepresentable rather than conventional — {@code CHARGE_AND_ALLOW} always carries the value
     * to write, so there is no proceeding arm that skips it.
     *
     * <p>And written with {@code commit()}, not {@code apply()}, following
     * the same reading of that question elsewhere. {@code apply()} returns once the value is in
     * memory and flushes on a background thread — which survives an orderly process death and not a
     * crash, so the one case the write must survive is the one it does not promise. The charge is on
     * disk before this method returns.
     *
     * @return true if the caller should rebuild now
     */
    public boolean claim(final String key) {
        final SharedPreferences p = prefs();
        final boolean readable = p != null && key != null;
        final MlsWindowBudget.Decision d = mPolicy.claim(key,
                p == null ? MlsWindowBudget.Store.UNREACHABLE : MlsWindowBudget.Store.AVAILABLE,
                readable ? p.getString(key, null) : null,
                SystemClock.elapsedRealtime());
        if (readable) {
            switch (d.storeAction()) {
                case WRITE:
                    p.edit().putString(key, d.value()).commit();
                    break;
                case DISCARD:
                    p.edit().remove(key).commit();
                    break;
                default:
                    break;
            }
        }
        switch (d.reason()) {
            case NO_KEY:
                return false;
            case NO_STORE:
                // No context means no bound, and an unbounded rebuild loop is worse than no rebuild
                // at all. Refuse rather than proceed unmeasured — MlsWindowBudget.REBUILD declares
                // OnNoStore.REFUSE and this line is what that posture reads like.
                LogUtil.w(TAG, "MlsRebuildLimiter: no context — refusing to rebuild " + key
                        + " because the rate bound cannot be recorded, and an unbounded rebuild loop "
                        + "is worse than a conversation that stays diverged one cycle longer");
                return false;
            case RECORD_UNREADABLE:
                // UNREADABLE IS NOT UNCHARGED, and it is not "no window" either — the same rule
                // MlsPeerGuard applies to its two records. Refuse once and discard, so a format skew
                // costs one deferred rebuild per conversation rather than a permanent block or a
                // silently restored allowance.
                LogUtil.e(TAG, "MlsRebuildLimiter: REFUSING to rebuild " + key + " — its rate-bound "
                        + "record is UNREADABLE, so we cannot say this rebuild is within budget. "
                        + "Discarding the record; the next attempt is judged on an empty one.");
                return false;
            case WINDOW_SPENT:
                LogUtil.w(TAG, "MlsRebuildLimiter: " + key + " has rebuilt " + d.count() + " times "
                        + "in this " + (mPolicy.windowMs() / 60000L) + "-minute window — NOT "
                        + "rebuilding again for at least another " + (d.remainingMs() / 60000L)
                        + " minutes of UPTIME. This is a RATE limit: the allowance refills, so the "
                        + "conversation is throttled, not abandoned, and the stalled-conversation "
                        + "notification's Try again clears it outright.");
                return false;
            default:
                break;
        }
        if (!d.mayProceed()) {
            // A posture with no arm, or an outcome added to the policy and not to this switch.
            // Refusing on an answer we did not understand is the only safe direction, and saying
            // WHICH answer is what stops it reading as an ordinary budget refusal.
            LogUtil.e(TAG, "MlsRebuildLimiter: refusing to rebuild " + key + " — the policy answered "
                    + d + ", which this adapter has no arm for. That is a DEFECT in the wiring, not "
                    + "a spent budget.");
            return false;
        }
        LogUtil.i(TAG, "MlsRebuildLimiter: charging rebuild " + (d.count() + 1) + "/"
                + mPolicy.maxPerWindow() + " for " + key + " in this window"
                + (d.adoptedLegacyRecord() ? " (adopted a legacy wall-clock record, keeping its "
                        + "count of " + d.count() + " and opening a fresh window)" : ""));
        return true;
    }

    /**
     * How many rebuilds are recorded for {@code key} in the current window.
     *
     * <p>Diagnostics only, and it answers <b>-1 for a record it cannot read</b> rather than 0 — a
     * reader that refuses a case must not spell it the same way as "nothing recorded", or the one
     * number a person would use to check the bound reports a full allowance for a corrupt file.
     */
    public int countInWindow(final String key) {
        final SharedPreferences p = prefs();
        if (p == null || key == null) return 0;
        // Never writes: a diagnostic must not be able to change the thing it reports on.
        return mPolicy.countIn(p.getString(key, null), SystemClock.elapsedRealtime());
    }

    /**
     * Operator reset — the "try again now" lever behind {@link MlsStalledNotifier}'s action.
     *
     * <p>Deliberately separate from the rolling window: a person pressing a button is a different
     * justification from time passing, and being able to tell the two apart in a log is what let us
     * distinguish a real repair from a retry that only looked like one.
     *
     * <p>It is also the bound on what the monotonic clock costs. After a reboot a spent window
     * refills only once six hours have passed IN UPTIME; this removes the record outright, so that
     * price is one tap rather than six hours.
     */
    public void reset(final String key) {
        final SharedPreferences p = prefs();
        if (p == null || key == null) return;
        p.edit().remove(key).commit();
        LogUtil.i(TAG, "MlsRebuildLimiter: rebuild rate bound RESET for " + key + " by request");
    }
}
