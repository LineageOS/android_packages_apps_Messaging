/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.e2ee;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import com.android.messaging.rcs.engine.mls.MlsConversationRecord;
import com.android.messaging.rcs.engine.mls.MlsPendingQueueAccess;
import com.android.messaging.rcs.engine.mls.MlsLog;
import com.android.messaging.rcs.engine.mls.MlsPendingQueue;
import com.android.messaging.util.LogUtil;

/**
 * Durable storage for {@link MlsPendingQueue} (RCC.16 §10.8), so messages parked while a group is
 * mid-transition survive process death.
 *
 * <p>One opaque blob per conversation, rewritten whole: a partial write of a reordering structure
 * would be indistinguishable from a drained bucket. A separate file from {@link MlsRecordStore},
 * because the record is small and written on every transition while a queue is large and written
 * rarely. Locking is the caller's: the transport holds the conversation lock across the engine call
 * and the write.
 */
public final class MlsPendingQueueStore implements MlsPerConversationState, MlsPendingQueueAccess {
    private static final String TAG = MlsLog.TAG;

    private static final String PREFS = "mls_pending_messages";

    private final Context mCtx;

    public MlsPendingQueueStore(final Context ctx) { mCtx = ctx.getApplicationContext(); }

    private SharedPreferences prefs() {
        return mCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Read one conversation's queue; never null. Unlike {@link MlsRecordStore#get}, a blob that
     * does not decode degrades to an empty queue (the worst case is that peers resend), with a
     * warning, as the log line is the only record of the loss.
     */
    public MlsPendingQueue load(final String identity, final byte[] groupId) {
        final String k = MlsConversationRecord.key(identity, groupId);
        final String v = prefs().getString(k, null);
        if (v == null) return new MlsPendingQueue();
        try {
            final byte[] blob = Base64.decode(v, Base64.NO_WRAP);
            final MlsPendingQueue q = MlsPendingQueue.fromBytes(blob);
            if (q.isEmpty() && blob.length > 8) {
                LogUtil.w(TAG, "MlsPendingQueueStore: the pending queue for " + k + " did not "
                        + "decode (" + blob.length + "B) — starting empty. Any messages parked "
                        + "there are LOST and will have to be resent by their senders.");
            }
            return q;
        } catch (final IllegalArgumentException notBase64) {
            LogUtil.w(TAG, "MlsPendingQueueStore: pending queue for " + k + " is not valid Base64 "
                    + "— starting empty. Parked messages are LOST.", notBase64);
            return new MlsPendingQueue();
        }
    }

    /**
     * Write one conversation's queue whole, with {@code commit()} so it is durable before the
     * caller releases the group lock. An empty queue removes the key, so "drained" and "never had
     * any" have one spelling.
     *
     * @return {@code null} on success, or the reason it failed
     */
    public String store(final String identity, final byte[] groupId, final MlsPendingQueue q) {
        final String k = MlsConversationRecord.key(identity, groupId);
        try {
            if (q == null || q.isEmpty()) {
                return prefs().edit().remove(k).commit() ? null : "commit() returned false for "
                        + k;
            }
            final String v = Base64.encodeToString(q.toBytes(), Base64.NO_WRAP);
            if (!prefs().edit().putString(k, v).commit()) {
                return "commit() returned false for " + k;
            }
            return null;
        } catch (final RuntimeException e) {
            LogUtil.e(TAG, "MlsPendingQueueStore: failed to persist the queue for " + k, e);
            return "encode/persist failed for " + k + ": " + e;
        }
    }

    /**
     * Forget one conversation's queue. This is the only purge: per RCC.16 §10.8 an era advance does
     * not prune a stale partition, so do not add an era cleanup.
     */
    public void remove(final String identity, final byte[] groupId) {
        prefs().edit().remove(MlsConversationRecord.key(identity, groupId)).commit();
    }

    /**
     * Part of the teardown. After a forget and re-establish the conversation is in a new era, so
     * every parked entry awaits an {@code (era, epoch)} it can never reach, and the queue has no
     * other bound or expiry that would remove it.
     *
     * @return 1 if a queue was addressed, 0 if there was no group id to address it by
     */
    @Override
    public int forgetConversation(final Scope scope) {
        if (scope.groupId == null || scope.groupId.length == 0) return 0;
        remove(scope.identity, scope.groupId);
        return 1;
    }
}
