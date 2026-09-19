/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.e2ee;

import com.android.messaging.datamodel.BugleDatabaseOperations;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.rcs.engine.mls.MlsConfig;
import com.android.messaging.rcs.engine.mls.MlsConversationKey;
import com.android.messaging.rcs.engine.mls.MlsReupgradeAccess;
import com.android.messaging.rcs.engine.mls.MlsDowngradeReason;
import com.android.messaging.rcs.engine.mls.MlsLog;
import com.android.messaging.rcs.engine.mls.MlsReupgradeState;
import com.android.messaging.util.LogUtil;

/**
 * The three re-upgrade columns on {@code conversations}, read and written whole.
 * {@link MlsReupgradeState} owns the arithmetic; this class owns the order in which the columns are
 * touched. See docs/mls/downgrade.md.
 *
 * <p>Failures degrade to {@link MlsReupgradeState#NONE} or a no-op, so bookkeeping never blocks a
 * downgrade: failing to record one costs a re-upgrade, failing to perform one costs
 * confidentiality.
 */
public final class MlsReupgradeStore implements MlsPerConversationState, MlsReupgradeAccess {
    private static final String TAG = MlsLog.TAG;

    private final MlsConfig mConfig;

    public MlsReupgradeStore(final MlsConfig config) {
        mConfig = config == null ? MlsConfig.defaults() : config;
    }

    /**
     * The stored state, or {@link MlsReupgradeState#NONE} if there is no row or the read failed.
     */
    public MlsReupgradeState load(final String conversationId) {
        try {
            final DatabaseWrapper db = DataModel.get().getDatabase();
            final MlsReupgradeState s =
                    BugleDatabaseOperations.getMlsReupgradeState(db, conversationId);
            return s == null ? MlsReupgradeState.NONE : s;
        } catch (final Throwable t) {
            LogUtil.w(TAG, "MlsReupgradeStore.load failed for "
                    + MlsConversationKey.forLog(conversationId), t);
            return MlsReupgradeState.NONE;
        }
    }

    /** Persist all three columns together. */
    public void store(final String conversationId, final MlsReupgradeState state) {
        if (state == null) return;
        try {
            final DatabaseWrapper db = DataModel.get().getDatabase();
            BugleDatabaseOperations.setMlsReupgradeState(db, conversationId, state);
        } catch (final Throwable t) {
            LogUtil.w(TAG, "MlsReupgradeStore.store failed for "
                    + MlsConversationKey.forLog(conversationId), t);
        }
    }

    /**
     * Record an unexpected downgrade, if this reason is one. Called before the local downgrade
     * runs, so the stamp survives a downgrade that returns early without a commit (offline, already
     * pending). Reasons with {@code expected == true} leave no stamp.
     *
     * @return the state after the stamp, for logging; never null
     */
    public MlsReupgradeState markDowngrade(final String conversationId,
            final MlsDowngradeReason reason, final long nowMs) {
        final MlsReupgradeState prior = load(conversationId);
        if (reason == null || reason.expected) {
            return prior;
        }
        // markUnexpectedDowngrade resets against the previous timestamp before stamping the new
        // one; the reverse order makes the stability window always read as zero.
        final MlsReupgradeState next =
                prior.markUnexpectedDowngrade(nowMs, mConfig.reupgradeStabilityWindowS);
        store(conversationId, next);
        LogUtil.i(TAG, "MlsReupgradeStore: " + MlsConversationKey.forLog(conversationId)
                + " stamped UNEXPECTED downgrade ("
                + reason + ") — " + next + "; next re-upgrade attempt no sooner than +"
                + next.backoffSeconds(mConfig.reupgradeBackoffMaxShift,
                        mConfig.reupgradeBackoffBaseS) + "s");
        return next;
    }

    /** Set or clear {@code mls_eagerly_downgraded}, the fast loop's only input. */
    public void setEagerlyDowngraded(final String conversationId, final boolean v) {
        store(conversationId, load(conversationId).withEagerlyDowngraded(v));
    }

    /**
     * The slow loop's gate: whether a re-upgrade may be attempted now, and if so, charge for it.
     * The counter is written before this returns {@code true}, so an attempt that crashes still
     * backs off.
     *
     * @return true if the caller should attempt a re-upgrade now
     */
    public boolean claimReupgradeAttempt(final String conversationId, final long nowMs) {
        final MlsReupgradeState s = load(conversationId);
        if (!s.hasUnexpectedDowngrade()) {
            return false;
        }
        if (s.withinBackoff(nowMs, mConfig.reupgradeBackoffMaxShift,
                mConfig.reupgradeBackoffBaseS)) {
            LogUtil.i(TAG, "MlsReupgradeStore: skipping MLS re-upgrade for "
                    + MlsConversationKey.forLog(conversationId)
                    + " — within the backoff period. Last downgrade: "
                    + s.lastUnexpectedDowngradeMs + ", next attempt: "
                    + s.nextAttemptAtMs(mConfig.reupgradeBackoffMaxShift,
                            mConfig.reupgradeBackoffBaseS));
            return false;                       // hard skip
        }
        store(conversationId, s.attempted());
        return true;
    }

    /** Zero all three columns together. */
    public void clear(final String conversationId) {
        store(conversationId, MlsReupgradeState.NONE);
    }

    /**
     * Part of the teardown: the retained columns are the backoff clock, so a forgotten conversation
     * would otherwise start deep in its backoff.
     *
     * @return 1 if a conversation id was available to clear by, else 0
     */
    @Override
    public int forgetConversation(final Scope scope) {
        if (scope.conversationId == null) return 0;
        clear(scope.conversationId);
        return 1;
    }
}
