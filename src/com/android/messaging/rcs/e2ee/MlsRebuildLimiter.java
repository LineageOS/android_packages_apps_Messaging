/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.e2ee;

import com.android.messaging.rcs.engine.mls.MlsConversationKey;
import com.android.messaging.rcs.engine.mls.MlsRebuildLimits;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import com.android.messaging.rcs.engine.mls.MlsLog;
import com.android.messaging.rcs.engine.mls.MlsWindowBudget;
import com.android.messaging.util.LogUtil;

/**
 * The rate bound on automatic conversation rebuilds: the {@code SharedPreferences} adapter for
 * {@link MlsWindowBudget#REBUILD}, which holds the policy. A rate, never a total; aged by
 * {@code elapsedRealtime}. See docs/mls/budgets.md.
 *
 * <p>Not an {@link MlsPerConversationState}: a rebuild starts by forgetting the conversation, so a
 * counter cleared by teardown would erase its own evidence and let an unrepairable conversation
 * rebuild in a loop. Keyed by canonical conversation key in its own preferences file; only the
 * window and {@link #reset} clear it.
 *
 * <p>It bounds our side only. A rebuild that re-creates a group the server holds re-Welcomes every
 * member, and that peer-facing cost is charged separately to {@code MlsPeerGuard}'s era budget.
 */
public final class MlsRebuildLimiter implements MlsRebuildLimits {
    private static final String TAG = MlsLog.TAG;
    private static final String PREFS = "mls_rebuild_limiter";

    private final Context mCtx;
    private final MlsWindowBudget mPolicy;

    public MlsRebuildLimiter(final Context ctx) {
        this(ctx, MlsWindowBudget.REBUILD);
    }

    /** Takes a policy rather than loose numbers, so every budget declares its posture. */
    public MlsRebuildLimiter(final Context ctx, final MlsWindowBudget policy) {
        mCtx = ctx == null ? null : ctx.getApplicationContext();
        mPolicy = policy;
    }

    private SharedPreferences prefs() {
        return mCtx == null ? null : mCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * May we rebuild {@code key} now, and if so, charge for it. The charge is written with
     * {@code commit()} before this returns, so a rebuild that crashes still counts.
     *
     * @return true if the caller should rebuild now
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
                LogUtil.w(TAG, "MlsRebuildLimiter: no context — refusing to rebuild "
                        + MlsConversationKey.forLog(key)
                        + " because the rate bound cannot be recorded, and an unbounded rebuild loop "
                        + "is worse than a conversation that stays diverged one cycle longer");
                return false;
            case RECORD_UNREADABLE:
                // Unreadable is not uncharged: refuse once and discard the record.
                LogUtil.e(TAG, "MlsRebuildLimiter: REFUSING to rebuild "
                        + MlsConversationKey.forLog(key)
                        + " — its rate-bound "
                        + "record is UNREADABLE, so we cannot say this rebuild is within budget. "
                        + "Discarding the record; the next attempt is judged on an empty one.");
                return false;
            case WINDOW_SPENT:
                LogUtil.w(TAG, "MlsRebuildLimiter: " + MlsConversationKey.forLog(key)
                        + " has rebuilt " + d.count() + " times "
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
            // An outcome this adapter has no arm for: refuse, and say it is a wiring defect.
            LogUtil.e(TAG, "MlsRebuildLimiter: refusing to rebuild "
                    + MlsConversationKey.forLog(key)
                    + " — the policy answered " + d
                    + ", which this adapter has no arm for. That is a DEFECT in the wiring, not "
                    + "a spent budget.");
            return false;
        }
        LogUtil.i(TAG, "MlsRebuildLimiter: charging rebuild " + (d.count() + 1) + "/"
                + mPolicy.maxPerWindow() + " for " + MlsConversationKey.forLog(key)
                + " in this window"
                + (d.adoptedLegacyRecord() ? " (adopted a legacy wall-clock record, keeping its "
                        + "count of " + d.count() + " and opening a fresh window)" : ""));
        return true;
    }

    /**
     * Rebuilds recorded for {@code key} in the current window; diagnostics only. -1 for an
     * unreadable record, so a corrupt file never reads as a full allowance.
     */
    public int countInWindow(final String key) {
        final SharedPreferences p = prefs();
        if (p == null || key == null) return 0;
        // Never writes.
        return mPolicy.countIn(p.getString(key, null), SystemClock.elapsedRealtime());
    }

    /**
     * Operator reset, behind the stall notification's Try again. Also bounds the cost of the
     * monotonic clock: after a reboot a spent window otherwise refills only after six hours of
     * uptime.
     */
    @Override
    public void reset(final String key) {
        final SharedPreferences p = prefs();
        if (p == null || key == null) return;
        p.edit().remove(key).commit();
        LogUtil.i(TAG, "MlsRebuildLimiter: rebuild rate bound RESET for "
                + MlsConversationKey.forLog(key) + " by request");
    }
}
