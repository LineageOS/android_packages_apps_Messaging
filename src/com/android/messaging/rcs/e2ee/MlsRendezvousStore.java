/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.e2ee;

import com.android.messaging.rcs.engine.mls.MlsMessageId;
import com.android.messaging.rcs.engine.mls.MlsRendezvousAccess;

import android.content.Context;
import android.content.SharedPreferences;

import com.android.messaging.rcs.engine.mls.MlsRendezvous;
import com.android.messaging.rcs.engine.mls.MlsLog;
import com.android.messaging.util.LogUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The rendezvous table: "have we already decrypted this message at this stage?". A miss costs a
 * duplicate, ratchet-advancing decrypt, so nothing here evicts on its own schedule.
 *
 * <p>Writes use {@code commit()}: the table closes the window between a decrypt and its chat row
 * being written, and {@code apply()} would leave the write inside that window.
 */
public final class MlsRendezvousStore implements MlsRendezvousAccess {

    private static final String TAG = MlsLog.TAG;
    private static final String PREFS = "mls_rendezvous";

    /**
     * Ceiling against unbounded growth. Rows are deleted per message at chat-row insert, so
     * reaching it means that path has stopped, which is logged rather than evicted around.
     */
    private static final int MAX_ROWS = 512;

    private final Context mCtx;

    public MlsRendezvousStore(final Context ctx) { mCtx = ctx.getApplicationContext(); }

    private SharedPreferences prefs() {
        return mCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Read before decrypting.
     *
     * @return the stored result, or {@code null} if not yet processed at this stage; the caller
     * then
     *         decrypts and calls {@link #put}
     */
    @Override
    public MlsRendezvous.Stored get(final String selfIdentity, final String remoteUserId,
            final String rcsMessageId, final MlsRendezvous.Stage stage) {
        if (rcsMessageId == null || rcsMessageId.isEmpty()) return null;
        final String k = MlsRendezvous.readKey(selfIdentity, remoteUserId, rcsMessageId, stage);
        final String v = prefs().getString(k, null);
        if (v == null) return null;
        final MlsRendezvous.Stored s = MlsRendezvous.Stored.decode(v);
        if (s == null) {
            // A corrupt row reads as absent, and is removed so it is not re-read forever.
            LogUtil.w(TAG, "MlsRendezvousStore: corrupt row for "
                    + MlsMessageId.forLog(rcsMessageId)
                    + " — treating as absent and removing");
            prefs().edit().remove(k).commit();
        }
        return s;
    }

    /** Store a decrypt's result so a duplicate is answered by replay. */
    @Override
    public void put(final String selfIdentity, final String remoteUserId,
            final String rcsMessageId, final MlsRendezvous.Stage stage,
            final MlsRendezvous.Stored result) {
        if (rcsMessageId == null || rcsMessageId.isEmpty() || result == null) return;
        final SharedPreferences p = prefs();
        if (p.getAll().size() >= MAX_ROWS) {
            LogUtil.e(TAG, "MlsRendezvousStore: " + MAX_ROWS
                    + " rows — the delete-on-chat-row-insert"
                    + " path has stopped running. Not storing " + MlsMessageId.forLog(rcsMessageId)
                    + "; a duplicate of "
                    + "it would be decrypted twice.");
            return;
        }
        final String k = MlsRendezvous.readKey(selfIdentity, remoteUserId, rcsMessageId, stage);
        if (!p.edit().putString(k, result.encode()).commit()) {
            LogUtil.w(TAG, "MlsRendezvousStore: could not persist "
                    + MlsMessageId.forLog(rcsMessageId)
                    + " — a duplicate would be decrypted again");
        }
    }

    /**
     * Delete at chat-row insert: every stage and every sender attribution of this message id, since
     * once it has a chat row there is nothing left to replay.
     *
     * @return how many rows were removed
     */
    @Override
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
    @Override
    public int size() { return prefs().getAll().size(); }

    /** Drop everything; used by {@code forget()} and the downgrade path. */
    public void clear() { prefs().edit().clear().commit(); }
}
