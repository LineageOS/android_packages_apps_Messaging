/*
 * Copyright (C) 2026 The Android Open Source Project
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
 * RCC.16 v4.0 §11.2.2: <em>"the client shall not attempt more than 50 External Commits per group per
 * day."</em>
 *
 * <h2>This class is a STORE, not a policy</h2>
 *
 * <p>The number, the window, the posture, the roll and the charge live in
 * {@link MlsWindowBudget#EXTERNAL_COMMIT}, host-tested. What is left here is the
 * {@code SharedPreferences} adapter, the operator reset and the refusal lines. The two budgets share
 * the POLICY SHAPE and the RECORD; they do not share a counter — different preference files, different
 * keys, so spending one still cannot spend the other, which is the property the next paragraph is
 * about.
 *
 * <p>Deliberately NOT {@link MlsRebuildLimiter} with different numbers. This is a <b>different
 * allowance from a different authority</b> — the spec bounds external commits, while the rebuild
 * limiter is our own invention to stop a broken conversation spinning. Sharing one counter would
 * make spending one silently spend the other, and the whole reason the external commit is worth
 * having is that <b>its budget is independent of the era-advancement quota</b> that strands a group
 * in the first place.
 *
 * <p>The window ROLLS rather than expiring into permanent exhaustion, for the reason the self-heal
 * budget one level down had to learn: a counter that never resets stops being
 * a rate limit and becomes a lifetime cap.
 *
 * <h2>THIS IS A DELIBERATE DIVERGENCE FROM GOOGLE MESSAGES — decided, not inherited</h2>
 *
 * <p><b>Google Messages does not implement this obligation at all.</b> RCC.16 v4.0 §11.2.2 and §11.2.3 place
 * client-side limits (50 external commits per group per day; a restriction on new eras), and that
 * client carries neither — checked against both halves of its implementation, native and managed,
 * so a single search missing them is not an explanation.
 *
 * <p>So Google Messages' shape is: keep no local quota model, attempt, and react to whatever token comes
 * back — {@code ServerFailureEraAdvancementQuotaReached(14)} is handled by going to END_MLS. Note
 * that token has NO client-side predicate anywhere: it is something the client PARSES, and server
 * behaviour behind it is opaque by construction. Nothing in Google Messages says when or why
 * the server refuses.
 *
 * <p>We keep the budget anyway, and the reason is worth stating so nobody "fixes" it toward parity: a
 * client that counts its own attempts cannot burn an allowance discovering it is exhausted, which is
 * exactly the failure hit when a refusing transport would otherwise have been probed 50
 * times a day. That is better engineering than Google Messages', and it is the kind of divergence to make on
 * purpose. If behavioural parity ever becomes the goal, this class is the thing to delete — not to
 * tune.
 *
 * <p><b>And there is now a second reason, which is not an engineering argument but a lived one.</b>
 * Between 2026-08-04 and 2026-09-07 unbounded MLS state churn from these devices wedged a real
 * third-party iPhone — a person outside the project, whose messaging we broke and who had no way to
 * ask us to stop. Self-imposed bounds are what stand between a reimplementation
 * and that outcome, and a bound only protects a peer while it is BELOW whatever the server tolerates.
 * Google Messages can afford no client-side quota because the server is Google's own and its operators can
 * see and stop us; we do not have that safety net, and our peers are not our test fleet. Weigh any
 * future argument for removing this against that, not only against §11.2.2.
 *
 * <h2>What this class does when it cannot reach a store — and what it used to do</h2>
 *
 * <p><b>It REFUSES.</b> This arm used to allow the commit UNCOUNTED, on
 * the argument that "refusing here would make an unwritable prefs file silently disable recovery,
 * which is a worse failure than an unbounded count". That was flipped on its own review,
 * because refusing a recovery rung has a real cost. Four things carried the decision:
 *
 * <ol>
 *   <li><b>The stated reason described a case this arm does not handle.</b> An unwritable
 *       preferences file does not produce a null from {@code getSharedPreferences} — it produces a
 *       store whose {@code commit()} returns false. This arm is "no application context", which is
 *       a different fault, and the argument that kept the allowance was about the other one.</li>
 *   <li><b>The arm is unreachable in product, measured rather than assumed.</b> The only
 *       construction site is {@code MlsProviderTransport.xcBudget()}, which passes the transport's
 *       own {@code mCtx}; that field is {@code ctx.getApplicationContext()} taken in a constructor
 *       that would already have thrown on a null {@code ctx}. So reaching this arm means the whole
 *       transport is unusable, which is not a state worth preserving an unbounded path for.</li>
 *   <li><b>A bound whose failure mode is no bound at all is not a bound.</b> The two paragraphs
 *       above about why we keep this when Google Messages does not are about a peer we cannot see and who
 *       has no way to ask us to stop. This was the one path in the class that reached exactly the
 *       state those paragraphs exist to prevent — and {@code MlsRebuildLimiter} refuses on the
 *       identical fact, with the identical structure, bounding an operation on the same recovery
 *       ladder. An earlier review found the divergence invisible rather than wrong; made visible,
 *       this is the row that did not survive being looked at.</li>
 *   <li><b>The cost is real and is bounded twice.</b> A refusal here is a SLOWER LADDER, not a dead
 *       end: {@code resyncViaExternalCommit} answers -1 and recovery falls through to the rebuild
 *       and then to the END_MLS terminal. And the stalled-conversation
 *       notification's Try again now clears this budget outright — which is why that lever landed
 *       BEFORE this flip. Adding a second refusing arm to a budget with no way back would have been
 *       strictly worse than leaving the hole.</li>
 * </ol>
 *
 * <h2>The clock is {@code elapsedRealtime}, not the wall clock</h2>
 *
 * <p>Found by a sweep of the package rather than reported: this window had the identical
 * defect, one window-length larger. A durable 24 h window aged by {@code System.currentTimeMillis()}
 * is rolled by any jump forward big enough — an NTP correction after a boot with a dead RTC will do
 * it — and the day's fifty come back with nothing in the log to say a day did not pass. That matters
 * more here than for the rebuild limiter, not less: this is a bound the SPEC places on us for the
 * benefit of the server and the group, and the paragraph above explains at length why we keep it when
 * Google Messages does not. A bound that a clock jump refills is not the bound we argued for.
 *
 * <p>Recorded here as an explicit, re-findable decision, because it would otherwise read to a later
 * maintainer as an undocumented divergence, i.e. as a bug to be tidied away.
 */
public final class MlsExternalCommitBudget {
    private static final String TAG = MlsLog.TAG;
    private static final String PREFS = "mls_external_commit_budget";

    private final Context mCtx;
    private final MlsWindowBudget mPolicy;

    public MlsExternalCommitBudget(final Context ctx) {
        this(ctx, MlsWindowBudget.EXTERNAL_COMMIT);
    }

    /** The seam a test would use; see {@link MlsRebuildLimiter#MlsRebuildLimiter(Context, MlsWindowBudget)}. */
    public MlsExternalCommitBudget(final Context ctx, final MlsWindowBudget policy) {
        mCtx = ctx == null ? null : ctx.getApplicationContext();
        mPolicy = policy;
    }

    private SharedPreferences prefs() {
        return mCtx == null ? null : mCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Claim one external commit for {@code key}, or refuse when the day's 50 are spent.
     *
     * @return true if the caller may proceed
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
                    // commit(), not apply(): apply() returns once the value is in memory and flushes
                    // on a background thread, so a charge can be lost in exactly the crash the bound
                    // is for.
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
                // REFUSE, and this arm used to ALLOW UNCOUNTED (decision D2, flipped on its own
                // review). MlsWindowBudget.EXTERNAL_COMMIT declares OnNoStore.REFUSE and this line
                // is what that posture reads like. The argument is in the class javadoc under
                // "What this class does when it cannot reach a store"; the short form is that a
                // bound whose failure mode is NO BOUND AT ALL is not a bound, and this is the one
                // path in a class written to protect a peer that reaches the state the class exists
                // to prevent.
                LogUtil.e(TAG, "MlsExternalCommitBudget: REFUSING an external commit for " + key
                        + " — no preferences store is reachable, so the §11.2.2 allowance cannot be "
                        + "counted and we cannot say this commit is within it. This arm used to "
                        + "allow the commit UNCOUNTED; it does not, because the whole reason we "
                        + "keep a bound Google Messages does not have is that our peers cannot ask us to "
                        + "stop. Recovery falls through to the rebuild and then to the END_MLS "
                        + "terminal, and the stalled-conversation notification's Try again clears "
                        + "this budget outright.");
                return false;
            case RECORD_UNREADABLE:
                // UNREADABLE IS NOT UNSPENT. Refuse once and discard, so a format skew costs one
                // deferred external commit per group rather than a permanent block or a silently
                // restored allowance. This is the one place this class refuses where the "no prefs"
                // arm allows, and the difference is real: there we know nothing was ever counted,
                // here we know something WAS and cannot read it.
                LogUtil.e(TAG, "MlsExternalCommitBudget: REFUSING an external commit for " + key
                        + " — its §11.2.2 record is UNREADABLE, so we cannot say this commit is "
                        + "within the day's " + mPolicy.maxPerWindow() + ". Discarding the record; "
                        + "the next attempt is judged on an empty one.");
                return false;
            case WINDOW_SPENT:
                LogUtil.w(TAG, "MlsExternalCommitBudget: " + key + " has spent all "
                        + mPolicy.maxPerWindow() + " external commits for today (RCC.16 v4.0 "
                        + "§11.2.2) — refusing for at least another " + (d.remainingMs() / 60000L)
                        + " minutes of UPTIME. A group that needs 50 resyncs in a day is not being "
                        + "fixed by the 51st.");
                return false;
            default:
                break;
        }
        if (!d.mayProceed()) {
            LogUtil.e(TAG, "MlsExternalCommitBudget: refusing an external commit for " + key
                    + " — the policy answered " + d + ", which this adapter has no arm for. That is "
                    + "a DEFECT in the wiring, not a spent budget.");
            return false;
        }
        LogUtil.i(TAG, "MlsExternalCommitBudget: charging external commit " + (d.count() + 1)
                + "/" + mPolicy.maxPerWindow() + " for " + key + " today");
        return true;
    }

    /**
     * Spent count for {@code key} in the current day.
     *
     * <p>Diagnostics only; never writes. Answers <b>-1</b> for a record it cannot read rather than
     * 0, so "corrupt" and "nothing spent" are not the same number.
     */
    public int spentToday(final String key) {
        final SharedPreferences p = prefs();
        if (p == null || key == null) return 0;
        return mPolicy.countIn(p.getString(key, null), SystemClock.elapsedRealtime());
    }

    /**
     * Clear this group's §11.2.2 allowance.
     *
     * <p><b>THIS HAS NO PRODUCTION CALLER</b>, verified by enumerating them. The
     * stalled-conversation notification's Try again clears the self-heal budget, the rebuild rate
     * bound, G2 and G4, and not this; a conversation whose recovery ladder is stuck behind a spent
     * external-commit allowance has no operator lever at all. That is being fixed in its own change,
     * because it interacts with D2's posture flip and both deserve their own review.
     */
    public void reset(final String key) {
        final SharedPreferences p = prefs();
        if (p == null || key == null) return;
        p.edit().remove(key).commit();
        LogUtil.w(TAG, "MlsExternalCommitBudget: §11.2.2 budget RESET for " + key + " by request");
    }
}
