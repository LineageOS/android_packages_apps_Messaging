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

import com.android.messaging.rcs.engine.mls.MlsRendezvous;
import com.android.messaging.rcs.engine.mls.MlsLog;
import com.android.messaging.util.LogUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * "Have we already decrypted this message?" — the rendezvous table (rework item 6.4, invariant 52).
 *
 * <p>SharedPreferences-backed, matching {@code MlsRecordStore}: same durability story, same
 * synchronous {@code commit()}, and no new storage technology to reason about for a table this
 * small. It is a cache in shape but NOT in semantics — a miss costs a duplicated ratchet-advancing
 * decrypt, so nothing here may evict on its own schedule.
 *
 * <h2>Why the commit must be synchronous</h2>
 *
 * <p>The window this table closes is precisely "the process died between the decrypt and the chat
 * row". An {@code apply()} would leave the write in that same window and reintroduce the bug it
 * exists to fix.
 */
public final class MlsRendezvousStore {

    // MlsLog.TAG ("RcsMls"), NOT LogUtil.BUGLE_TAG. Every other line in the MLS flow logs
    // under RcsMls, and a store that logs elsewhere is invisible to anyone grepping the flow —
    // which cost a debugging round when this store's lines were the only ones a MessagingApp
    // filter showed and the transport's were the ones that mattered.
    private static final String TAG = MlsLog.TAG;
    private static final String PREFS = "mls_rendezvous";

    /**
     * A ceiling so a pathological peer cannot grow the file without bound. Generous: the table is
     * emptied per message at chat-row insert, so reaching this means the delete side has stopped
     * running, which is worth a loud line rather than silent eviction.
     */
    private static final int MAX_ROWS = 512;

    private final Context mCtx;

    public MlsRendezvousStore(final Context ctx) { mCtx = ctx.getApplicationContext(); }

    private SharedPreferences prefs() {
        return mCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * READ BEFORE DECRYPT.
     *
     * @return the stored result, or {@code null} if this message has not been processed at this
     *         stage — in which case the caller decrypts and then calls {@link #put}
     */
    public MlsRendezvous.Stored get(final String selfIdentity, final String remoteUserId,
            final String rcsMessageId, final MlsRendezvous.Stage stage) {
        if (rcsMessageId == null || rcsMessageId.isEmpty()) return null;
        final String k = MlsRendezvous.readKey(selfIdentity, remoteUserId, rcsMessageId, stage);
        final String v = prefs().getString(k, null);
        if (v == null) return null;
        final MlsRendezvous.Stored s = MlsRendezvous.Stored.decode(v);
        if (s == null) {
            // A corrupt row reads as ABSENT. Drop it so it cannot be re-read forever.
            LogUtil.w(TAG, "MlsRendezvousStore: corrupt row for " + rcsMessageId
                    + " — treating as absent and removing");
            prefs().edit().remove(k).commit();
        }
        return s;
    }

    /** Store the result of a decrypt so a duplicate can be answered by replay. */
    public void put(final String selfIdentity, final String remoteUserId,
            final String rcsMessageId, final MlsRendezvous.Stage stage,
            final MlsRendezvous.Stored result) {
        if (rcsMessageId == null || rcsMessageId.isEmpty() || result == null) return;
        final SharedPreferences p = prefs();
        if (p.getAll().size() >= MAX_ROWS) {
            LogUtil.e(TAG, "MlsRendezvousStore: " + MAX_ROWS + " rows — the delete-on-chat-row-insert"
                    + " path has stopped running. Not storing " + rcsMessageId + "; a duplicate of "
                    + "it would be decrypted twice.");
            return;
        }
        final String k = MlsRendezvous.readKey(selfIdentity, remoteUserId, rcsMessageId, stage);
        // commit(), not apply() — see the class doc: apply() leaves the write in exactly the window
        // this table exists to close.
        if (!p.edit().putString(k, result.encode()).commit()) {
            LogUtil.w(TAG, "MlsRendezvousStore: could not persist " + rcsMessageId
                    + " — a duplicate would be decrypted again");
        }
    }

    /**
     * DELETE ON CHAT-ROW INSERT — broader than the read, on purpose.
     *
     * <p>Drops every stage and every sender-attribution of this message id. Once it has a chat row
     * there is nothing left to replay, and a narrower delete would leak a row per message forever
     * while looking correct in every duplicate test.
     *
     * @return how many rows were removed
     */
    public int deleteForMessage(final String selfIdentity, final String rcsMessageId) {
        if (rcsMessageId == null || rcsMessageId.isEmpty()) return 0;
        final SharedPreferences p = prefs();
        final Map<String, ?> all = p.getAll();
        final List<String> doomed = new ArrayList<>();
        for (final String k : all.keySet()) {
            if (MlsRendezvous.keyMatchesMessage(k, selfIdentity, rcsMessageId)) doomed.add(k);
        }
        if (doomed.isEmpty()) return 0;
        final SharedPreferences.Editor e = p.edit();
        for (final String k : doomed) e.remove(k);
        e.commit();
        return doomed.size();
    }

    /** Row count, for diagnostics. */
    public int size() { return prefs().getAll().size(); }

    /** Drop everything. Used by {@code forget()} and the downgrade path. */
    public void clear() { prefs().edit().clear().commit(); }
}
