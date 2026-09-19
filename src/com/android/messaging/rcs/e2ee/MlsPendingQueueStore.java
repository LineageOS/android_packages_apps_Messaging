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
import android.util.Base64;

import com.android.messaging.rcs.engine.mls.MlsConversationRecord;
import com.android.messaging.rcs.engine.mls.MlsLog;
import com.android.messaging.rcs.engine.mls.MlsPendingQueue;
import com.android.messaging.util.LogUtil;

/**
 * Persistence for {@link MlsPendingQueue} — rework item {@code 6.7}, §10.8.
 *
 * <p>The half of {@code 6.7} that could not be fixed by moving code: <b>the old buffer was
 * in-memory, so every parked message was lost on process death.</b> A message deferred while the
 * group was mid-era-advance simply vanished if the app was killed in between, and nothing on either
 * side could tell that from a message that had been delivered.
 *
 * <h2>One blob per conversation, rewritten wholesale</h2>
 *
 * <p>Google Messages persists this as opaque blobs in {@code mls_pending_message_blobs}, <b>rewritten
 * wholesale</b>, and <i>"the host never inspects them"</i>. Ours matches that shape, and wholesale is
 * the load-bearing part: a partial write of a reordering structure is <b>worse</b> than no write,
 * because a bucket missing its second half is indistinguishable from a bucket that was fully drained
 * — one loses messages silently, the other is at least visible as an empty queue.
 *
 * <p>Separate prefs file from {@link MlsRecordStore} for the same reason Google Messages uses separate blobs:
 * the record is small and written on every transition, while a queue holding a few ciphertexts is
 * large and written rarely. Sharing one blob would rewrite every parked message on every state
 * change.
 *
 * <p>Locking is the caller's, exactly as with {@link MlsRecordStore} — {@code MlsProviderTransport}
 * holds the conversation lock across the engine call and the write.
 */
public final class MlsPendingQueueStore implements MlsPerConversationState {
    private static final String TAG = MlsLog.TAG;

    private static final String PREFS = "mls_pending_messages";

    private final Context mCtx;

    public MlsPendingQueueStore(final Context ctx) { mCtx = ctx.getApplicationContext(); }

    private SharedPreferences prefs() {
        return mCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Read one conversation's queue. Never null.
     *
     * <p><b>Two-valued, not three</b>, and that is the deliberate difference from
     * {@link MlsRecordStore#get}. A record that fails to decode is an ERROR there, because starting
     * fresh would silently reset a group to {@code Unknown} and send it through the whole recovery
     * ladder. An unreadable QUEUE is different in kind: the worst case is that some parked messages
     * are lost and the peer resends them, whereas refusing to proceed would wedge a conversation that
     * is otherwise perfectly healthy. So a malformed blob degrades to empty — <b>loudly</b>, because
     * the log line is the only evidence that anything was dropped.
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
     * Write one conversation's queue WHOLE.
     *
     * <p>{@code commit()} rather than {@code apply()}, for {@link MlsRecordStore#put}'s reason: the
     * caller holds the group lock across the engine call and this write, and the point of that
     * bracket is that the write is durable before the lock is released.
     *
     * <p>An EMPTY queue removes the key rather than storing an empty blob. Not tidiness — an empty
     * blob and an absent key must not be two spellings of the same thing, or a future reader has to
     * decide which one means "drained" and which means "never had any".
     *
     * @return {@code null} on success, or the reason it failed
     */
    public String store(final String identity, final byte[] groupId, final MlsPendingQueue q) {
        final String k = MlsConversationRecord.key(identity, groupId);
        try {
            if (q == null || q.isEmpty()) {
                return prefs().edit().remove(k).commit() ? null : "commit() returned false for " + k;
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
     * Forget one conversation's queue entirely.
     *
     * <p>The ONLY purge. §10.8 is explicit that an era advance does <b>not</b> prune a stale
     * partition — only two functions in Google Messages touch the era map, and neither of them prunes, so an
     * abandoned era's messages are retained until the whole per-group blob is deleted. Anything that
     * looks like "clean up old eras" is a divergence, not a fix.
     */
    public void remove(final String identity, final byte[] groupId) {
        prefs().edit().remove(MlsConversationRecord.key(identity, groupId)).commit();
    }

    /**
     * {@link #remove} had no caller, so the "only purge" never ran.
     *
     * <p>The consequence is specific rather than general untidiness. Parked messages are addressed
     * by the moment they await — {@code (era, epoch)} — and a forget-then-re-establish moves the
     * conversation to a NEW era. Every retained entry therefore awaits a moment the re-joined group
     * can never reach, because eras do not go backwards. They are not merely stale: they are
     * unreachable by construction, and §10.8's queue has no capacity bound, no eviction and no TTL
     * (proven exhaustive), so nothing else would ever have removed them.
     *
     * <p>That "(proven exhaustive)" is a CITATION, not an independent finding — it is the same
     * inherited absence {@link com.android.messaging.rcs.engine.mls.MlsPendingQueue} rests on, and
     * its falsifier lives there. Note which way this purge fails if the absence
     * is wrong: a bound in Google Messages would mean this purge is doing work Google Messages' own eviction would
     * have done, which is harmless. The claim is load-bearing for the QUEUE, not for this method.
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
