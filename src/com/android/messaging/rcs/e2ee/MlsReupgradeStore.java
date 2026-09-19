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

import com.android.messaging.datamodel.BugleDatabaseOperations;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.rcs.engine.mls.MlsConfig;
import com.android.messaging.rcs.engine.mls.MlsDowngradeReason;
import com.android.messaging.rcs.engine.mls.MlsLog;
import com.android.messaging.rcs.engine.mls.MlsReupgradeState;
import com.android.messaging.util.LogUtil;

/**
 * SQLite-backed re-upgrade bookkeeping — rework item {@code 11.7}, §9.7l.
 *
 * <p>The app-layer half of "coming back". {@link MlsReupgradeState} owns the arithmetic and is
 * host-tested; this owns the three columns and the ORDER in which they are touched, which is where
 * invariants 105 and 106 live.
 *
 * <p>Read/write failures degrade to {@link MlsReupgradeState#NONE} / no-op rather than throwing,
 * matching {@link ConversationBitsStore}: a storage hiccup must never block a downgrade. The
 * asymmetry is deliberate and worth stating — <b>failing to record a downgrade costs a re-upgrade;
 * failing to perform one costs confidentiality</b>. So the downgrade proceeds and the bookkeeping is
 * best-effort, never the other way round.
 */
public final class MlsReupgradeStore implements MlsPerConversationState {
    private static final String TAG = MlsLog.TAG;

    private final MlsConfig mConfig;

    public MlsReupgradeStore(final MlsConfig config) {
        mConfig = config == null ? MlsConfig.defaults() : config;
    }

    /** The stored state, or {@link MlsReupgradeState#NONE} if there is no row or the read failed. */
    public MlsReupgradeState load(final String conversationId) {
        try {
            final DatabaseWrapper db = DataModel.get().getDatabase();
            final MlsReupgradeState s =
                    BugleDatabaseOperations.getMlsReupgradeState(db, conversationId);
            return s == null ? MlsReupgradeState.NONE : s;
        } catch (final Throwable t) {
            LogUtil.w(TAG, "MlsReupgradeStore.load failed for " + conversationId, t);
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
            LogUtil.w(TAG, "MlsReupgradeStore.store failed for " + conversationId, t);
        }
    }

    /**
     * <b>Invariant 105 — STAMP BEFORE COMMIT.</b> Record an unexpected downgrade, if this reason is
     * one.
     *
     * <p>Called <b>before</b> the local downgrade runs, so re-upgrade eligibility survives a
     * downgrade that never emits a commit at all — offline, already-pending, or {@code y}-skipped.
     * Stamping afterwards loses exactly the cases where coming back matters most, because those are
     * the ones where the downgrade path returns early.
     *
     * <p>Reasons with {@code expected == true} are a deliberate no-op: {@code w} means "do not plan
     * to come back", so Phoenix's own downgrade and a real capability loss leave no stamp.
     *
     * @return the state after the stamp, for logging; never null
     */
    public MlsReupgradeState markDowngrade(final String conversationId,
            final MlsDowngradeReason reason, final long nowMs) {
        final MlsReupgradeState prior = load(conversationId);
        if (reason == null || reason.expected) {
            // Expected: this downgrade is not something we plan to recover from.
            return prior;
        }
        // Invariant 106 (RESET-THEN-STAMP) is inside markUnexpectedDowngrade: the stability window
        // is consulted against the PREVIOUS timestamp and the counter reset before the new one is
        // written. Reversing it makes the window always read as zero.
        final MlsReupgradeState next =
                prior.markUnexpectedDowngrade(nowMs, mConfig.reupgradeStabilityWindowS);
        store(conversationId, next);
        LogUtil.i(TAG, "MlsReupgradeStore: " + conversationId + " stamped UNEXPECTED downgrade ("
                + reason + ") — " + next + "; next re-upgrade attempt no sooner than +"
                + next.backoffSeconds(mConfig.reupgradeBackoffMaxShift,
                        mConfig.reupgradeBackoffBaseS) + "s");
        return next;
    }

    /** Set or clear {@code mls_eagerly_downgraded} — loop 2's only input. */
    public void setEagerlyDowngraded(final String conversationId, final boolean v) {
        store(conversationId, load(conversationId).withEagerlyDowngraded(v));
    }

    /**
     * Loop 1's gate — whether a re-upgrade may be attempted now, and if so, charge for it.
     *
     * <p><b>Invariant 107 — INCREMENT BEFORE ATTEMPT.</b> The counter is written before this returns
     * {@code true}, so a revive that CRASHES still backs off. Incrementing on success only turns a
     * reproducible crash into an unthrottled retry loop, which is the exact failure the counter
     * exists to bound.
     *
     * @return true if the caller should attempt a re-upgrade now
     */
    public boolean claimReupgradeAttempt(final String conversationId, final long nowMs) {
        final MlsReupgradeState s = load(conversationId);
        if (!s.hasUnexpectedDowngrade()) {
            return false;                       // loop 1 has no opinion about this conversation
        }
        if (s.withinBackoff(nowMs, mConfig.reupgradeBackoffMaxShift,
                mConfig.reupgradeBackoffBaseS)) {
            LogUtil.i(TAG, "MlsReupgradeStore: skipping MLS re-upgrade for " + conversationId
                    + " — within the backoff period. Last downgrade: "
                    + s.lastUnexpectedDowngradeMs + ", next attempt: "
                    + s.nextAttemptAtMs(mConfig.reupgradeBackoffMaxShift,
                            mConfig.reupgradeBackoffBaseS));
            return false;                       // HARD SKIP
        }
        store(conversationId, s.attempted());   // charge BEFORE the attempt
        return true;
    }

    /** {@code clearMlsState} — zero all three columns together. */
    public void clear(final String conversationId) {
        store(conversationId, MlsReupgradeState.NONE);
    }

    /**
     * Join the teardown so a forgotten conversation also forgets its re-upgrade backoff.
     *
     * <p>{@link #clear} already had callers — the re-upgrade path and two downgrade sites — so
     * unlike the record and queue stores this was not dead. It was simply not part of teardown, and
     * the retained columns are the backoff clock: {@code attemptCount} and
     * {@code lastUnexpectedDowngradeMs} feed {@code nextAttemptAtMs}, so a conversation forgotten
     * after several failed re-upgrades comes back already deep in its backoff and is HARD SKIPPED
     * on its first attempt as a fresh conversation.
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
