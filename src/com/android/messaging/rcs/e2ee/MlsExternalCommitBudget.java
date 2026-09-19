/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.messaging.rcs.e2ee;

import com.android.messaging.rcs.engine.mls.MlsConversationKey;
import com.android.messaging.rcs.engine.mls.MlsExternalCommitLimits;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import com.android.messaging.rcs.engine.mls.MlsLog;
import com.android.messaging.rcs.engine.mls.MlsWindowBudget;
import com.android.messaging.util.LogUtil;

/**
 * RCC.16 §11.2.2: at most 50 external commits per group per day. The {@code SharedPreferences}
 * adapter for {@link MlsWindowBudget#EXTERNAL_COMMIT}, which holds the policy; aged by
 * {@code elapsedRealtime}. See docs/mls/budgets.md.
 *
 * <p>A separate counter from {@link MlsRebuildLimiter} and from the era budget, in its own
 * preferences file: the value of an external commit is that it can repair a group the era quota
 * has stranded.
 *
 * <p>Some clients keep no local quota and only react to the server's quota error. We keep one so
 * that probing an exhausted allowance cannot happen, and because a bound protects peers only while
 * it sits below what the server tolerates. With no store reachable the budget refuses: recovery
 * falls through to a rebuild, and Try again clears the allowance.
 */
public final class MlsExternalCommitBudget implements MlsExternalCommitLimits {
    private static final String TAG = MlsLog.TAG;
    private static final String PREFS = "mls_external_commit_budget";

    private final Context mCtx;
    private final MlsWindowBudget mPolicy;

    public MlsExternalCommitBudget(final Context ctx) {
        this(ctx, MlsWindowBudget.EXTERNAL_COMMIT);
    }

    /** Takes a policy rather than loose numbers, as {@link MlsRebuildLimiter} does. */
    public MlsExternalCommitBudget(final Context ctx, final MlsWindowBudget policy) {
        mCtx = ctx == null ? null : ctx.getApplicationContext();
        mPolicy = policy;
    }

    private SharedPreferences prefs() {
        return mCtx == null ? null : mCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Claim one external commit for {@code key}, or refuse when the day's allowance is spent.
     *
     * @return true if the caller may proceed
     */
    @Override
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
                    // commit(): on disk before the commit is attempted.
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
                // No store means no bound, so the declared no-store posture refuses.
                LogUtil.e(TAG, "MlsExternalCommitBudget: REFUSING an external commit for "
                        + MlsConversationKey.forLog(key)
                        + " — no preferences store is reachable, so the §11.2.2 allowance cannot be "
                        + "counted and we cannot say this commit is within it. This arm does not "
                        + "allow the commit UNCOUNTED, because the whole reason we "
                        + "keep a bound some peers do not have is that our peers cannot ask us to "
                        + "stop. Recovery falls through to the rebuild and then to the END_MLS "
                        + "terminal, and the stalled-conversation notification's Try again clears "
                        + "this budget outright.");
                return false;
            case RECORD_UNREADABLE:
                // Unreadable is not unspent: refuse once and discard the record.
                LogUtil.e(TAG, "MlsExternalCommitBudget: REFUSING an external commit for "
                        + MlsConversationKey.forLog(key)
                        + " — its §11.2.2 record is UNREADABLE, so we cannot say this commit is "
                        + "within the day's " + mPolicy.maxPerWindow() + ". Discarding the record; "
                        + "the next attempt is judged on an empty one.");
                return false;
            case WINDOW_SPENT:
                LogUtil.w(TAG, "MlsExternalCommitBudget: " + MlsConversationKey.forLog(key)
                        + " has spent all "
                        + mPolicy.maxPerWindow() + " external commits for today (RCC.16 v4.0 "
                        + "§11.2.2) — refusing for at least another " + (d.remainingMs() / 60000L)
                        + " minutes of UPTIME. A group that needs 50 resyncs in a day is not being "
                        + "fixed by the 51st.");
                return false;
            default:
                break;
        }
        if (!d.mayProceed()) {
            LogUtil.e(TAG, "MlsExternalCommitBudget: refusing an external commit for "
                    + MlsConversationKey.forLog(key)
                    + " — the policy answered " + d
                    + ", which this adapter has no arm for. That is "
                    + "a DEFECT in the wiring, not a spent budget.");
            return false;
        }
        LogUtil.i(TAG, "MlsExternalCommitBudget: charging external commit " + (d.count() + 1)
                + "/" + mPolicy.maxPerWindow() + " for " + MlsConversationKey.forLog(key)
                + " today");
        return true;
    }

    /**
     * Spent count for {@code key} today; diagnostics only, never writes. -1 for an unreadable
     * record.
     */
    public int spentToday(final String key) {
        final SharedPreferences p = prefs();
        if (p == null || key == null) return 0;
        return mPolicy.countIn(p.getString(key, null), SystemClock.elapsedRealtime());
    }

    /**
     * Clear this group's RCC.16 §11.2.2 allowance; reached from the stall notification's Try again.
     */
    @Override
    public void reset(final String key) {
        final SharedPreferences p = prefs();
        if (p == null || key == null) return;
        p.edit().remove(key).commit();
        LogUtil.w(TAG, "MlsExternalCommitBudget: §11.2.2 budget RESET for "
                + MlsConversationKey.forLog(key) + " by request");
    }
}
