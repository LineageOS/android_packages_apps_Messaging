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

import android.content.ContentValues;
import android.database.Cursor;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DatabaseHelper;
import com.android.messaging.datamodel.DatabaseHelper.MlsResendColumns;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.rcs.engine.mls.MlsResendRecord;
import com.android.messaging.rcs.engine.mls.MlsLog;
import com.android.messaging.util.LogUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The durable resend register (rework item 7.4, §11.1, invariant 63).
 *
 * <p>DAO over {@link DatabaseHelper#MLS_RESENDS_TABLE}. The arithmetic lives in
 * {@link MlsResendRecord} so it is host-testable; everything here is SQL.
 *
 * <h2>Why this is a table and not a preference file</h2>
 *
 * <p>The other MLS stores ({@code MlsRecordStore}, {@code MlsRendezvousStore},
 * {@link MlsCiphertextCache}) are SharedPreferences-backed, and this deliberately is not. Those hold
 * one blob per key and are read by exact key. This one answers two aggregate questions — "what is
 * the highest count in this chain" and "how many resends have gone to this peer" — on every negative
 * receipt. Doing that over a preference map means loading every entry and filtering in Java, which
 * is how a bounded store acquires an eviction policy, and an eviction here silently resets an
 * escalation ladder. A real table with two indices has neither problem.
 *
 * <h2>Durability is the point, not a bonus</h2>
 *
 * <p>This replaces an in-memory {@code HashMap<String,Integer>} that was cleared by process death.
 * A peer that had failed four times therefore looked like a first-time failure after any restart,
 * and the escalation that repairs a genuinely diverged group could never be reached on a device that
 * had been rebooted in between. §11.1 states the property directly: the counter is durable <b>by
 * construction</b> because it is derived from rows, with no counter held anywhere.
 */
public final class MlsResendLedger {

    // MlsLog.TAG ("RcsMls"), NOT LogUtil.BUGLE_TAG. Every other line in the MLS flow logs
    // under RcsMls, and a store that logs elsewhere is invisible to anyone grepping the flow —
    // which cost a debugging round when this store's lines were the only ones a MessagingApp
    // filter showed and the transport's were the ones that mattered.
    private static final String TAG = MlsLog.TAG;

    private MlsResendLedger() {}

    @Nullable
    private static DatabaseWrapper db() {
        try {
            return DataModel.get().getDatabase();
        } catch (final RuntimeException notReady) {
            // Reachable before the DataModel is built (early boot, some tests). A ledger miss is
            // survivable — it costs one rung of the ladder — whereas throwing here would abort the
            // resend of a message we can still deliver.
            LogUtil.w(TAG, "MlsResendLedger: no database yet", notReady);
            return null;
        }
    }

    /**
     * Resolve any message id to the ROOT of its resend chain.
     *
     * <p><b>Call this before looking up a reported id anywhere.</b> A peer reports whichever id it
     * saw, so the second failure of one message names a RESEND's id — and a resend has no chat row,
     * so a body lookup on it misses and the message is declared unrecoverable while the original sits
     * in the database. Ids that are not resends are returned unchanged, which makes this safe to call
     * unconditionally.
     */
    public static String rootOf(final String rcsMessageId) {
        if (TextUtils.isEmpty(rcsMessageId)) return rcsMessageId;
        final DatabaseWrapper d = db();
        if (d == null) return rcsMessageId;
        try (Cursor c = d.query(DatabaseHelper.MLS_RESENDS_TABLE,
                new String[] { MlsResendColumns.ORIGINAL_RCS_MESSAGE_ID },
                MlsResendColumns.RCS_MESSAGE_ID + "=?", new String[] { rcsMessageId },
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                final String root = c.getString(0);
                if (!TextUtils.isEmpty(root)) return root;
            }
        } catch (final RuntimeException e) {
            LogUtil.w(TAG, "MlsResendLedger.rootOf(" + rcsMessageId + ") failed", e);
        }
        return rcsMessageId;
    }

    /**
     * The conversation a RESEND was sent in — {@code "g:<rcsGroupId>"} or {@code "p:<e164>"}, or
     * {@code null} if this id is not a resend we recorded.
     *
     * <p>This is the ONLY place a resend's conversation is written down. A resend is minted as a
     * bare UUID and never gets a chat row, so without this row a caller asking "was this a group
     * message?" gets "no" from every other source — and "no" is what makes a single delivery
     * receipt terminal, releasing the material the rest of the group's failures still need.
     */
    @Nullable
    public static String conversationKeyOf(final String rcsMessageId) {
        if (TextUtils.isEmpty(rcsMessageId)) return null;
        final DatabaseWrapper d = db();
        if (d == null) return null;
        try (Cursor c = d.query(DatabaseHelper.MLS_RESENDS_TABLE,
                new String[] { MlsResendColumns.CONVERSATION_KEY },
                MlsResendColumns.RCS_MESSAGE_ID + "=?", new String[] { rcsMessageId },
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                final String key = c.getString(0);
                if (!TextUtils.isEmpty(key)) return key;
            }
        } catch (final RuntimeException e) {
            LogUtil.w(TAG, "MlsResendLedger.conversationKeyOf(" + rcsMessageId + ") failed", e);
        }
        return null;
    }

    /** Every resend recorded for one chain, oldest first. */
    public static List<MlsResendRecord> siblings(final String originalRcsMessageId) {
        final List<MlsResendRecord> out = new ArrayList<>();
        if (TextUtils.isEmpty(originalRcsMessageId)) return out;
        final DatabaseWrapper d = db();
        if (d == null) return out;
        try (Cursor c = d.query(DatabaseHelper.MLS_RESENDS_TABLE,
                new String[] {
                        MlsResendColumns.RCS_MESSAGE_ID,
                        MlsResendColumns.ORIGINAL_RCS_MESSAGE_ID,
                        MlsResendColumns.MANUAL_RESEND_OF,
                        MlsResendColumns.RECIPIENT_ADDRESS,
                        MlsResendColumns.RECIPIENT_CLIENT_ID,
                        MlsResendColumns.FTD_RESEND_COUNT,
                        MlsResendColumns.TIMESTAMP,
                },
                MlsResendColumns.ORIGINAL_RCS_MESSAGE_ID + "=?",
                new String[] { originalRcsMessageId },
                null, null, MlsResendColumns.FTD_RESEND_COUNT + " ASC")) {
            while (c != null && c.moveToNext()) {
                out.add(new MlsResendRecord(c.getString(0), c.getString(1), c.getString(2),
                        c.getString(3), c.getString(4), c.getInt(5), c.getLong(6)));
            }
        } catch (final RuntimeException e) {
            LogUtil.w(TAG, "MlsResendLedger.siblings(" + originalRcsMessageId + ") failed", e);
        }
        return out;
    }

    /**
     * Record a resend and return the NEW message id to send it under.
     *
     * <p>The id is minted here rather than by the caller so that the id which goes on the wire and
     * the id in the register cannot diverge — that divergence is exactly what made the resend path
     * dead code before the two id namespaces were unified.
     *
     * @param originalRcsMessageId the chain ROOT — pass {@link #rootOf} of whatever was reported
     * @param replacesRcsMessageId the id being replaced: the root for a first resend, the previous
     *                             resend's id afterwards
     * @return the new {@code rcs_message_id}, or {@code null} if nothing was recorded — in which
     *         case do NOT send, because an unrecorded resend has no counter and the ladder stalls
     */
    @Nullable
    public static String recordResend(final String originalRcsMessageId,
            final String replacesRcsMessageId, final String recipientAddress,
            @Nullable final String recipientClientId, @Nullable final String conversationKey,
            final long nowMs) {
        if (TextUtils.isEmpty(originalRcsMessageId)) return null;
        final DatabaseWrapper d = db();
        if (d == null) return null;
        final int count = MlsResendRecord.nextFtdResendCount(siblings(originalRcsMessageId));
        final String newId = UUID.randomUUID().toString();
        final ContentValues v = new ContentValues();
        v.put(MlsResendColumns.RCS_MESSAGE_ID, newId);
        v.put(MlsResendColumns.ORIGINAL_RCS_MESSAGE_ID, originalRcsMessageId);
        v.put(MlsResendColumns.MANUAL_RESEND_OF,
                TextUtils.isEmpty(replacesRcsMessageId) ? originalRcsMessageId
                        : replacesRcsMessageId);
        v.put(MlsResendColumns.RECIPIENT_ADDRESS, recipientAddress == null ? "" : recipientAddress);
        v.put(MlsResendColumns.RECIPIENT_CLIENT_ID,
                recipientClientId == null ? "" : recipientClientId);
        v.put(MlsResendColumns.FTD_RESEND_COUNT, count);
        v.put(MlsResendColumns.CONVERSATION_KEY, conversationKey == null ? "" : conversationKey);
        v.put(MlsResendColumns.TIMESTAMP, nowMs);
        try {
            d.insert(DatabaseHelper.MLS_RESENDS_TABLE, null, v);
        } catch (final RuntimeException e) {
            LogUtil.e(TAG, "MlsResendLedger: could not record a resend of " + originalRcsMessageId
                    + " — NOT sending it. An unrecorded resend carries no count, so the escalation "
                    + "ladder would never advance and a diverged peer would be resent to forever.",
                    e);
            return null;
        }
        LogUtil.i(TAG, "MlsResendLedger: resend " + count + " of " + originalRcsMessageId
                + " → new id " + newId + " for " + recipientAddress);
        return newId;
    }

    /**
     * How many resends we have already sent to one peer, across every chain in a conversation.
     *
     * <p>This is the durable half of the escalation ladder: past a threshold, resending is not
     * working and the peer's STATE is the problem, so the remedy becomes a repair rather than
     * another copy of the message.
     *
     * <p>Scoped by conversation as well as address because the same peer can be in several groups
     * and a group that is failing says nothing about one that is healthy.
     */
    public static int resendsToPeer(@Nullable final String conversationKey,
            final String recipientAddress) {
        return resendsToPeerSince(conversationKey, recipientAddress, /*sinceMs=*/ 0L);
    }

    /**
     * As {@link #resendsToPeer}, but counting only rows at or after {@code sinceMs}.
     *
     * <p><b>The window is the whole point</b> ({@link MlsSendRetentionPolicy}'s
     * sibling {@code MlsResendBudget}). The count alone is durable and therefore accumulates
     * forever, so one burst disables resends in a conversation permanently — measured: 82 rows
     * written in forty seconds left every later legitimate resend refused until an operator deleted
     * them by hand. Google Messages pairs its count bound with a time bound for exactly this reason
     * (FTD_RETRY_LIMIT_EXCEEDED(12) and FTD_TIME_LIMIT_EXCEEDED(26)).
     *
     * @param sinceMs oldest timestamp to count; 0 counts everything, which is the old behaviour
     */
    /**
     * REPEAT resends only — how many rows in the window are a SECOND-or-later resend of a chain we
     * had already resent. This is the number the storm guard actually wants.
     *
     * <p>{@link #resendsToPeerSince} counts every row, which conflates two unrelated things: a LOOP
     * (one message resent over and over — the 2026-08-08 incident of 99 resends in forty seconds)
     * and legitimate RECOVERY (N distinct messages each resent once). Budgeting on the total meant a
     * burst with 13 distinct failures could recover only {@code MAX_PER_WINDOW}=2 of them and the
     * rest were dropped by design — measured 2026-08-09 as ~2% permanent loss at burst rate, with
     * the bodies present and no refusal logged.
     *
     * <p>Counting repeats keeps the storm guard exactly as strong — a loop is by definition repeats
     * on one chain, so it still trips the budget almost immediately — while letting each distinct
     * message have the one resend §10.3 exists to give it. Per-chain growth stays bounded
     * independently by the ledger's rung count and by the emitter's own attempt cap.
     *
     * @return {@code COUNT(*) - COUNT(DISTINCT original)} over the window, i.e. rows beyond the
     *         first for each chain
     */
    public static int repeatResendsToPeerSince(@Nullable final String conversationKey,
            final String recipientAddress, final long sinceMs) {
        if (TextUtils.isEmpty(recipientAddress)) return 0;
        final DatabaseWrapper d = db();
        if (d == null) return 0;
        final String timeClause = sinceMs > 0L
                ? " AND " + MlsResendColumns.TIMESTAMP + ">=" + sinceMs : "";
        final String where = (TextUtils.isEmpty(conversationKey)
                ? MlsResendColumns.RECIPIENT_ADDRESS + "=?"
                : MlsResendColumns.RECIPIENT_ADDRESS + "=? AND "
                        + MlsResendColumns.CONVERSATION_KEY + "=?") + timeClause;
        final String[] args = TextUtils.isEmpty(conversationKey)
                ? new String[] { recipientAddress }
                : new String[] { recipientAddress, conversationKey };
        try (Cursor c = d.rawQuery("SELECT COUNT(*) - COUNT(DISTINCT "
                + MlsResendColumns.ORIGINAL_RCS_MESSAGE_ID + ") FROM "
                + DatabaseHelper.MLS_RESENDS_TABLE + " WHERE " + where, args)) {
            if (c != null && c.moveToFirst()) return Math.max(0, c.getInt(0));
        } catch (final RuntimeException e) {
            LogUtil.w(TAG, "MlsResendLedger.repeatResendsToPeerSince failed", e);
        }
        return 0;
    }

    public static int resendsToPeerSince(@Nullable final String conversationKey,
            final String recipientAddress, final long sinceMs) {
        if (TextUtils.isEmpty(recipientAddress)) return 0;
        final DatabaseWrapper d = db();
        if (d == null) return 0;
        final String timeClause = sinceMs > 0L
                ? " AND " + MlsResendColumns.TIMESTAMP + ">=" + sinceMs : "";
        final String where = (TextUtils.isEmpty(conversationKey)
                ? MlsResendColumns.RECIPIENT_ADDRESS + "=?"
                : MlsResendColumns.RECIPIENT_ADDRESS + "=? AND "
                        + MlsResendColumns.CONVERSATION_KEY + "=?") + timeClause;
        final String[] args = TextUtils.isEmpty(conversationKey)
                ? new String[] { recipientAddress }
                : new String[] { recipientAddress, conversationKey };
        try (Cursor c = d.rawQuery("SELECT COUNT(*) FROM " + DatabaseHelper.MLS_RESENDS_TABLE
                + " WHERE " + where, args)) {
            if (c != null && c.moveToFirst()) return c.getInt(0);
        } catch (final RuntimeException e) {
            LogUtil.w(TAG, "MlsResendLedger.resendsToPeerSince failed", e);
        }
        return 0;
    }

    /**
     * Forget every resend in one conversation — call when the group has been REPAIRED.
     *
     * <p>An era advance re-Welcomes every member from fresh key packages, so the failures that drove
     * these rows are no longer evidence about the peer. Keeping them would leave the ladder at its
     * top rung and turn the very next decrypt failure — including a first, ordinary one — straight
     * into another era advance.
     *
     * @return rows removed
     */
    public static int forgetConversation(final String conversationKey) {
        if (TextUtils.isEmpty(conversationKey)) return 0;
        final DatabaseWrapper d = db();
        if (d == null) return 0;
        try {
            return d.delete(DatabaseHelper.MLS_RESENDS_TABLE,
                    MlsResendColumns.CONVERSATION_KEY + "=?", new String[] { conversationKey });
        } catch (final RuntimeException e) {
            LogUtil.w(TAG, "MlsResendLedger.forgetConversation failed", e);
            return 0;
        }
    }

    /**
     * The teardown adapter.
     *
     * <p>This class is deliberately all-static (it is a DAO with no instance state), so it cannot
     * itself implement {@link MlsPerConversationState}. A nested implementation keeps the ledger in
     * the enumerable set the guard checks, rather than making it the one container the registry has
     * to remember by hand — which is precisely the class of omission that guard is about.
     *
     * <p>{@link #forgetConversation(String)} was already called on the ONE path that legitimately
     * resets the ladder (a successful era advance during escalation, where every member is
     * re-Welcomed so the recorded failures stop being evidence). It was never called on forget,
     * where the same argument applies with more force: the group is gone entirely.
     */
    public static final class Teardown implements MlsPerConversationState {
        @Override
        public int forgetConversation(final Scope scope) {
            // Guarded HERE even though the static below is null-safe. The rule the guard test
            // enforces is "read a nullable scope field only behind a check in this body", and an
            // implementation that relies on its callee's guard makes that rule unverifiable by
            // inspection — which is how the alias removal stayed a no-op in the wrong key space.
            if (scope.canonicalKey == null) return 0;
            return MlsResendLedger.forgetConversation(scope.canonicalKey);
        }
    }

    /** Forget one chain — call when the original was finally delivered. */
    public static int forgetChain(final String originalRcsMessageId) {
        if (TextUtils.isEmpty(originalRcsMessageId)) return 0;
        final DatabaseWrapper d = db();
        if (d == null) return 0;
        try {
            return d.delete(DatabaseHelper.MLS_RESENDS_TABLE,
                    MlsResendColumns.ORIGINAL_RCS_MESSAGE_ID + "=?",
                    new String[] { originalRcsMessageId });
        } catch (final RuntimeException e) {
            LogUtil.w(TAG, "MlsResendLedger.forgetChain failed", e);
            return 0;
        }
    }
}
