/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
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
import com.android.messaging.rcs.engine.mls.MlsMessageId;
import com.android.messaging.rcs.engine.mls.MlsResendRecord;
import com.android.messaging.rcs.engine.mls.MlsLog;
import com.android.messaging.rcs.log.LogMask;
import com.android.messaging.util.LogUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The durable resend register (RCC.16 §11.1): a DAO over {@link DatabaseHelper#MLS_RESENDS_TABLE},
 * with the arithmetic in {@link MlsResendRecord}. Counts are derived from rows, so they survive
 * process death by construction. A table rather than a preference file because the ladder asks
 * aggregate questions on every negative receipt. See docs/mls/health-and-recovery.md.
 */
public final class MlsResendLedger {

    private static final String TAG = MlsLog.TAG;

    private MlsResendLedger() {}

    @Nullable
    private static DatabaseWrapper db() {
        try {
            return DataModel.get().getDatabase();
        } catch (final RuntimeException notReady) {
            // Reachable before the DataModel exists. A ledger miss costs one rung; a throw would
            // abort a deliverable resend.
            LogUtil.w(TAG, "MlsResendLedger: no database yet", notReady);
            return null;
        }
    }

    /**
     * Resolve any message id to the root of its resend chain; call before looking up a reported id
     * anywhere. A second failure names a resend's id, which has no chat row. Ids that are not
     * resends come back unchanged.
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
            LogUtil.w(TAG, "MlsResendLedger.rootOf(" + MlsMessageId.forLog(rcsMessageId)
                    + ") failed", e);
        }
        return rcsMessageId;
    }

    /**
     * The conversation a resend was sent in ({@code "g:<rcsGroupId>"} or {@code "p:<e164>"}), or
     * {@code null} if this id is not a recorded resend. A resend has no chat row, so this is the
     * only record of its conversation; without it a group resend would look like a 1:1, whose
     * single delivery receipt is terminal.
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
            LogUtil.w(TAG, "MlsResendLedger.conversationKeyOf(" + MlsMessageId.forLog(rcsMessageId)
                    + ") failed", e);
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
            LogUtil.w(TAG, "MlsResendLedger.siblings(" + MlsMessageId.forLog(originalRcsMessageId)
                    + ") failed", e);
        }
        return out;
    }

    /**
     * Record a resend and return the new message id to send it under, minted here so the wire id
     * and the register cannot diverge.
     *
     * @param originalRcsMessageId the chain root ({@link #rootOf} of whatever was reported)
     * @param replacesRcsMessageId the root for a first resend, the previous resend's id afterwards
     * @return the new {@code rcs_message_id}, or {@code null} if nothing was recorded; then do not
     *         send, because an unrecorded resend never advances the ladder
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
            LogUtil.e(TAG, "MlsResendLedger: could not record a resend of "
                    + MlsMessageId.forLog(originalRcsMessageId)
                    + " — NOT sending it. An unrecorded resend carries no count, so the escalation "
                    + "ladder would never advance and a diverged peer would be resent to forever.",
                    e);
            return null;
        }
        LogUtil.i(TAG, "MlsResendLedger: resend " + count + " of "
                + MlsMessageId.forLog(originalRcsMessageId)
                + " → new id " + newId + " for " + LogMask.number(recipientAddress));
        return newId;
    }

    /**
     * Resends already sent to one peer across every chain in a conversation. Past a threshold the
     * peer's state is the problem and the remedy becomes a repair. Scoped by conversation because a
     * failing group says nothing about a healthy one.
     */
    public static int resendsToPeer(@Nullable final String conversationKey,
            final String recipientAddress) {
        return resendsToPeerSince(conversationKey, recipientAddress, /*sinceMs=*/ 0L);
    }

    /**
     * Repeat resends only: rows in the window that are a second or later resend of their chain. A
     * resend loop is repeats on one chain and still trips the budget, while N distinct messages
     * each resent once, which is recovery, do not.
     *
     * @return {@code COUNT(*) - COUNT(DISTINCT original)} over the window
     */
    public static int repeatResendsToPeerSince(@Nullable final String conversationKey,
            final String recipientAddress, final long sinceMs) {
        if (TextUtils.isEmpty(recipientAddress)) return 0;
        final DatabaseWrapper d = db();
        if (d == null) return 0;
        final String where = (TextUtils.isEmpty(conversationKey)
                ? MlsResendColumns.RECIPIENT_ADDRESS + "=?"
                : MlsResendColumns.RECIPIENT_ADDRESS + "=? AND "
                        + MlsResendColumns.CONVERSATION_KEY + "=?") + sinceClause(sinceMs);
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

    /**
     * As {@link #resendsToPeer}, counting only rows at or after {@code sinceMs}; 0 counts every
     * row but a retired one. The window matters because the count is durable: without it one burst
     * disables resends in a conversation for good.
     */
    public static int resendsToPeerSince(@Nullable final String conversationKey,
            final String recipientAddress, final long sinceMs) {
        if (TextUtils.isEmpty(recipientAddress)) return 0;
        final DatabaseWrapper d = db();
        if (d == null) return 0;
        final String where = (TextUtils.isEmpty(conversationKey)
                ? MlsResendColumns.RECIPIENT_ADDRESS + "=?"
                : MlsResendColumns.RECIPIENT_ADDRESS + "=? AND "
                        + MlsResendColumns.CONVERSATION_KEY + "=?") + sinceClause(sinceMs);
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
     * Forget every resend in one conversation, once the group has been repaired: after an era
     * advance re-Welcomes every member, old failures are no longer evidence, and keeping them would
     * turn the next ordinary failure straight into another era advance.
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
     * The teardown adapter; the ledger is all-static, so a nested class joins the
     * {@link MlsPerConversationState} set.
     */
    public static final class Teardown implements MlsPerConversationState {
        @Override
        public int forgetConversation(final Scope scope) {
            // Checked here although the callee is null-safe: the guard test requires each nullable
            // scope field to be checked in the body that reads it.
            if (scope.canonicalKey == null) return 0;
            return MlsResendLedger.forgetConversation(scope.canonicalKey);
        }
    }

    /**
     * Retire one chain once the message was delivered: its rows stop counting as escalation
     * evidence but stay, because they are what maps a receipt naming a resend (the delivery
     * receipt itself, and a displayed receipt later) back to the original row. A retired row's
     * {@link MlsResendColumns#TIMESTAMP} holds minus the retirement time, which every count
     * excludes ({@link #sinceClause}) and {@link #forgetRetiredChains} ages.
     *
     * @return rows retired
     */
    public static int retireChain(final String originalRcsMessageId) {
        if (TextUtils.isEmpty(originalRcsMessageId)) return 0;
        final DatabaseWrapper d = db();
        if (d == null) return 0;
        final ContentValues v = new ContentValues();
        v.put(MlsResendColumns.TIMESTAMP, -System.currentTimeMillis());
        try {
            return d.update(DatabaseHelper.MLS_RESENDS_TABLE, v,
                    MlsResendColumns.ORIGINAL_RCS_MESSAGE_ID + "=? AND "
                            + MlsResendColumns.TIMESTAMP + ">=0",
                    new String[] { originalRcsMessageId });
        } catch (final RuntimeException e) {
            LogUtil.w(TAG, "MlsResendLedger.retireChain failed", e);
            return 0;
        }
    }

    /**
     * Delete the rows of chains retired at least {@code maxAgeMs} ago; after that a receipt naming
     * one of their resends no longer reaches the original row.
     *
     * @return rows deleted
     */
    public static int forgetRetiredChains(final long maxAgeMs) {
        final DatabaseWrapper d = db();
        if (d == null) return 0;
        final long retiredBefore = System.currentTimeMillis() - Math.max(0L, maxAgeMs);
        try {
            // Inlined like sinceClause: a bound argument is text, and a long needs no escaping.
            return d.delete(DatabaseHelper.MLS_RESENDS_TABLE, MlsResendColumns.TIMESTAMP + "<0 AND "
                    + MlsResendColumns.TIMESTAMP + ">=" + (-retiredBefore), null);
        } catch (final RuntimeException e) {
            LogUtil.w(TAG, "MlsResendLedger.forgetRetiredChains failed", e);
            return 0;
        }
    }

    /**
     * The time bound of a count: rows at or after {@code sinceMs}, and never a retired row, whose
     * negative timestamp {@link #retireChain} wrote.
     */
    private static String sinceClause(final long sinceMs) {
        return " AND " + MlsResendColumns.TIMESTAMP + ">=" + Math.max(0L, sinceMs);
    }
}
