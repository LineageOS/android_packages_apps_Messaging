/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.e2ee;

import com.android.messaging.rcs.engine.mls.MlsMessageId;
import com.android.messaging.rcs.engine.mls.MlsPendingBodyAccess;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Base64;

import com.android.messaging.rcs.engine.mls.MlsLog;
import com.android.messaging.rcs.engine.mls.MlsMonotonicAge;
import com.android.messaging.rcs.engine.mls.MlsSendRetentionPolicy;
import com.android.messaging.util.LogUtil;

/**
 * The outbound framed body of a message still in flight, so a resend never depends on the chat
 * database. The send path dispatches before it inserts the chat row, and a peer's failure report
 * arrives fastest exactly when the epochs have diverged, so the row can be missing when a resend
 * is needed. See docs/mls/health-and-recovery.md.
 *
 * <p>A sibling of {@link MlsCiphertextCache}, same key and lifecycle: the ciphertext serves a
 * replay, while a failure report needs a re-encrypt at the repaired epoch, which needs the body.
 * The body is stored as {@link MlsProviderTransport#encryptForSend} received it, before the
 * per-encrypt generation stamp, so it can be passed straight back to a fresh encrypt.
 *
 * <p>This is plaintext at rest, a second copy of what the chat database already holds in the same
 * sandbox; it is bounded and released at the same terminal transition as the ciphertext.
 */
public final class MlsPendingBodyStore implements MlsPendingBodyAccess {

    private static final String TAG = MlsLog.TAG;
    private static final String PREFS = "mls_pending_body";

    /**
     * Ceiling. At the cap, expired entries are swept first, then the oldest entry is evicted so the
     * newest send always has resend material.
     */
    private static final int MAX_ENTRIES = 1024;

    private final Context mCtx;

    public MlsPendingBodyStore(final Context ctx) { mCtx = ctx.getApplicationContext(); }

    private SharedPreferences prefs() {
        return mCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Remember the framed body for a message being sealed. Called from the seal path, before the
     * message reaches the wire, whenever the chat row commits.
     */
    @Override
    public void put(final String rcsMessageId, final byte[] framedBody) {
        if (rcsMessageId == null || rcsMessageId.isEmpty()) return;
        if (framedBody == null || framedBody.length == 0) return;
        final SharedPreferences p = prefs();
        if (!p.contains(rcsMessageId) && p.getAll().size() >= MAX_ENTRIES) {
            // Sweep before evicting, so the cap heals itself: entries left by a process death or a
            // receipt that never arrived are what the retention window is for.
            final java.util.List<String> aged = sweepExpired(MlsSendRetentionPolicy.DEFAULT_MAX_AGE_MS);
            if (!aged.isEmpty()) {
                LogUtil.i(TAG, "MlsPendingBodyStore: at the " + MAX_ENTRIES + "-entry cap — swept "
                        + aged.size() + " expired entr(ies) to make room for " + MlsMessageId.forLog(rcsMessageId) + ".");
            }
            if (prefs().getAll().size() >= MAX_ENTRIES) {
                // Evict the oldest rather than decline the newest: a store full of unacknowledged
                // entries would otherwise decline every new message. The oldest has had the longest
                // chance to be acknowledged and still has the chat row to fall back on.
                final String oldest = oldestKey(p, SystemClock.elapsedRealtime());
                if (oldest != null) {
                    p.edit().remove(oldest).commit();
                    LogUtil.w(TAG, "MlsPendingBodyStore: at the " + MAX_ENTRIES + "-entry cap with "
                            + "nothing expirable — evicted the OLDEST (" + oldest + ") to make room "
                            + "for " + MlsMessageId.forLog(rcsMessageId) + ". Sends are outrunning their receipts.");
                } else {
                    LogUtil.e(TAG, "MlsPendingBodyStore: at the cap and could not identify an oldest "
                            + "entry — NOT storing " + MlsMessageId.forLog(rcsMessageId) + ".");
                    return;
                }
            }
        }
        if (!p.edit().putString(rcsMessageId, encode(SystemClock.elapsedRealtime(), framedBody))
                .commit()) {
            LogUtil.w(TAG, "MlsPendingBodyStore: could not persist " + MlsMessageId.forLog(rcsMessageId));
        }
    }

    /**
     * Field separator. Not a Base64 character in any alphabet, so the three formats are told apart
     * by field count alone.
     */
    private static final String STAMP_SEP = "|";

    /**
     * The current format's leading field. Stored values come in three formats, by field count:
     * <ul>
     *   <li>1 field, {@code <b64>}: never stamped; expired by the sweep.</li>
     *   <li>2 fields, {@code <wallMs>|<b64>}: wall-clock stamped; adopted at the current elapsed
     *       reading.</li>
     *   <li>3 fields, {@code 1|<elapsedMs>|<b64>}: stamped with {@code elapsedRealtime}.</li>
     * </ul>
     * The count decides; the version token lets a later format say which one it is reading.
     */
    private static final String VERSION = "1";

    /** The current format: {@code 1|<elapsedMs>|<b64 payload>}. */
    private static String encode(final long elapsedMs, final byte[] framedBody) {
        return VERSION + STAMP_SEP + elapsedMs + STAMP_SEP
                + Base64.encodeToString(framedBody, Base64.NO_WRAP);
    }

    /**
     * The elapsed stamp in {@code value}, or {@link MlsSendRetentionPolicy#UNSTAMPED} for a 1-field
     * or wall-stamped entry; {@link #isLegacyWallStamped} tells those two apart.
     */
    private static long stampOf(final String value) {
        if (value == null) return MlsSendRetentionPolicy.UNSTAMPED;
        final String[] f = value.split("\\" + STAMP_SEP, -1);
        if (f.length < 3) return MlsSendRetentionPolicy.UNSTAMPED;
        try {
            final long at = Long.parseLong(f[1]);
            return at < 0L ? MlsSendRetentionPolicy.UNSTAMPED : at;
        } catch (final NumberFormatException e) {
            return MlsSendRetentionPolicy.UNSTAMPED;
        }
    }

    /**
     * Whether {@code value} is a 2-field wall-stamped entry. It is adopted rather than expired, so
     * upgrading the format does not drop every message in flight across it.
     */
    private static boolean isLegacyWallStamped(final String value) {
        if (value == null) return false;
        final String[] f = value.split("\\" + STAMP_SEP, -1);
        if (f.length != 2 || f[0].isEmpty()) return false;
        try {
            Long.parseLong(f[0]);
            return true;
        } catch (final NumberFormatException notAStamp) {
            return false;
        }
    }

    /** The payload in {@code value}, in any of the three generations. */
    private static String payloadOf(final String value) {
        if (value == null) return null;
        final int i = value.lastIndexOf(STAMP_SEP);
        return i < 0 ? value : value.substring(i + 1);
    }


    /**
     * The key with the largest provable age, or {@code null}. Ordered by {@link
     * MlsMonotonicAge#ageMs}, not by stamp: elapsed stamps compare only within one boot, and a
     * pre-reboot entry holds a larger number than everything written since.
     */
    private static String oldestKey(final SharedPreferences p, final long nowElapsedMs) {
        String best = null;
        long bestAge = -1L;
        for (final java.util.Map.Entry<String, ?> e : p.getAll().entrySet()) {
            final Object v = e.getValue();
            if (!(v instanceof String)) continue;
            final long at = stampOf((String) v);
            if (at < 0L) continue;
            final long age = MlsMonotonicAge.ageMs(at, nowElapsedMs);
            if (age > bestAge) { bestAge = age; best = e.getKey(); }
        }
        return best;
    }

    /**
     * Drop entries older than {@code maxAgeMs} (see {@link MlsSendRetentionPolicy}), and adopt
     * wall-stamped ones.
     *
     * @return the ids dropped, so the caller can release their ciphertext in the same sweep
     */
    @Override
    public java.util.List<String> sweepExpired(final long maxAgeMs) {
        final java.util.List<String> gone = new java.util.ArrayList<>();
        final java.util.Map<String, String> adopt = new java.util.LinkedHashMap<>();
        final SharedPreferences p = prefs();
        final long now = SystemClock.elapsedRealtime();
        for (final java.util.Map.Entry<String, ?> e : p.getAll().entrySet()) {
            final Object v = e.getValue();
            if (!(v instanceof String)) continue;
            final String value = (String) v;
            if (isLegacyWallStamped(value)) {
                // Adopt rather than judge on a wall reading: a fresh window on the monotonic clock
                // can only retain it longer, and makes it ageable.
                adopt.put(e.getKey(), encode(now, Base64.decode(payloadOf(value), Base64.NO_WRAP)));
                continue;
            }
            if (MlsSendRetentionPolicy.expiredMonotonic(now, stampOf(value), maxAgeMs)) {
                gone.add(e.getKey());
            }
        }
        if (!gone.isEmpty() || !adopt.isEmpty()) {
            final SharedPreferences.Editor ed = p.edit();
            for (final String id : gone) ed.remove(id);
            for (final java.util.Map.Entry<String, String> e : adopt.entrySet()) {
                ed.putString(e.getKey(), e.getValue());
            }
            ed.commit();
        }
        if (!adopt.isEmpty()) {
            LogUtil.i(TAG, "MlsPendingBodyStore: adopted " + adopt.size() + " entr"
                    + (adopt.size() == 1 ? "y" : "ies") + " written with the wall clock — each now "
                    + "has a fresh " + (maxAgeMs / 3600000L) + "h window timed with "
                    + "elapsedRealtime. They are retained LONGER than before, never "
                    + "shorter.");
        }
        if (!gone.isEmpty()) {
            LogUtil.i(TAG, "MlsPendingBodyStore: aged out " + gone.size() + " sent message(s) past "
                    + (maxAgeMs / 3600000L) + "h. A resend for one of them is no longer possible; "
                    + "that is the intended end of the retention window, not a fault.");
        }
        return gone;
    }

    /** The framed body for {@code rcsMessageId}, or {@code null}. */
    @Override
    public byte[] get(final String rcsMessageId) {
        if (rcsMessageId == null || rcsMessageId.isEmpty()) return null;
        final String v = prefs().getString(rcsMessageId, null);
        if (v == null) return null;
        try {
            final byte[] b = Base64.decode(payloadOf(v), Base64.NO_WRAP);
            return (b == null || b.length == 0) ? null : b;
        } catch (final Throwable t) {
            LogUtil.w(TAG, "MlsPendingBodyStore: corrupt entry for "
                    + MlsMessageId.forLog(rcsMessageId)
                    + " — removing");
            prefs().edit().remove(rcsMessageId).commit();
            return null;
        }
    }

    /**
     * Release one message's body at a terminal state (delivered or permanently failed), alongside
     * the ciphertext. Not at "sent": a sent message can still need a resend.
     */
    @Override
    public void release(final String rcsMessageId) {
        if (rcsMessageId == null || rcsMessageId.isEmpty()) return;
        if (prefs().contains(rcsMessageId)) {
            prefs().edit().remove(rcsMessageId).commit();
        }
    }

    /** Entry count, for diagnostics. */
    public int size() { return prefs().getAll().size(); }
}
