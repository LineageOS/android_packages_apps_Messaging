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
import android.os.SystemClock;
import android.util.Base64;

import com.android.messaging.rcs.engine.mls.MlsLog;
import com.android.messaging.rcs.engine.mls.MlsMonotonicAge;
import com.android.messaging.rcs.engine.mls.MlsSendRetentionPolicy;
import com.android.messaging.util.LogUtil;

/**
 * The outbound FRAMED BODY of a message still in flight, so a resend never depends on the chat
 * database.
 *
 * <h2>The bug this exists to close, which is an ORDERING bug and not a slow write</h2>
 *
 * <p>{@code InsertNewMessageAction} <b>sends first and inserts the row second</b>: the RCS dispatch
 * completes, and only then does the database transaction insert the message and write its
 * {@code rcs_message_id}. So a message reaches the wire before any record of it exists.
 *
 * <p>{@code resendOriginal} recovered its text from that record. A peer that reports a decrypt
 * failure before the insert commits therefore found nothing, and we declared the message
 * permanently unrecoverable — logging <i>"this message stays lost for them"</i> while the text
 * landed in the database moments later and stayed there. Device-reproduced 2026-08-02: the FTD
 * arrived 2.4 s after the send, the lookup missed, and a query against the same device afterwards
 * returned the row with the correct id and text.
 *
 * <p><b>The fast FTD is the normal case, not an edge case.</b> A peer reports immediately on
 * decrypt failure, and decrypt failure is exactly the situation where the two sides' epochs have
 * diverged — so the race fires precisely when the resend matters most.
 *
 * <h2>Why the body and not the ciphertext</h2>
 *
 * <p>{@link MlsCiphertextCache} already persists the sealed CIPHERTEXT for the ordinary retry case,
 * where replaying the exact bytes is required. That is the wrong remedy for an FTD: the peer could
 * not decrypt those bytes, so replaying them fails identically. An FTD needs a re-encrypt at the
 * repaired epoch, and a re-encrypt needs the PLAINTEXT body.
 *
 * <p>So the two stores are deliberate siblings with the same key and the same lifecycle, holding
 * the two things the two different remedies need. Neither replaces the other.
 *
 * <h2>What is stored, and why it is the framed body</h2>
 *
 * <p>The {@code framedBody} exactly as {@link MlsProviderTransport#encryptForSend} received it —
 * <b>before</b> the generation stamp, which is applied per-encrypt. That makes a stored body a
 * drop-in argument for a fresh {@code encryptForSend} call, so a resend re-stamps at the epoch it
 * now holds rather than the one the report named. Recovering text and re-framing would produce the
 * same bytes by a longer route and only for text messages.
 *
 * <h2>Confidentiality</h2>
 *
 * <p>This holds plaintext at rest. That is not a new exposure: the same content is already in
 * {@code parts.text} in the clear in the chat database on the same device, under the same app
 * sandbox. It IS a second copy, so it is bounded and released on the same terminal transition as
 * the ciphertext cache rather than lingering.
 */
public final class MlsPendingBodyStore {

    // MlsLog.TAG, matching every other line in the MLS flow — a store that logs elsewhere is
    // invisible to anyone grepping the flow.
    private static final String TAG = MlsLog.TAG;
    private static final String PREFS = "mls_pending_body";

    /**
     * Ceiling, mirroring {@link MlsCiphertextCache}. On reaching it we log and DECLINE rather than
     * evicting: evicting the oldest would silently re-open this bug on the messages that have been
     * retrying longest, which are the ones where a resend matters most.
     */
    private static final int MAX_ENTRIES = 1024;

    private final Context mCtx;

    public MlsPendingBodyStore(final Context ctx) { mCtx = ctx.getApplicationContext(); }

    private SharedPreferences prefs() {
        return mCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Remember the framed body for a message being sealed.
     *
     * <p>Called from the seal path, which is strictly BEFORE the message reaches the wire — that
     * ordering is the whole point, and it is what makes this immune to the race regardless of when
     * the chat row commits.
     */
    public void put(final String rcsMessageId, final byte[] framedBody) {
        if (rcsMessageId == null || rcsMessageId.isEmpty()) return;
        if (framedBody == null || framedBody.length == 0) return;
        final SharedPreferences p = prefs();
        if (!p.contains(rcsMessageId) && p.getAll().size() >= MAX_ENTRIES) {
            // SWEEP BEFORE DECLINING — the cap must be self-healing.
            //
            // The expiry sweep used to run from exactly ONE place: the GROUP branch of
            // releaseSealedOnPositiveReceipt. A device doing only 1:1 traffic never takes that
            // branch, so the retention window was never enforced and the store could sit at the cap
            // FOREVER — device-observed: a device pegged at 256 hours after the traffic that
            // filled it, with every later send silently losing its resend safety net.
            //
            // The entries that wedge it are debris: a message sealed while the app later died, or a
            // receipt that never arrived. Those are exactly what the retention window is for, so
            // age them out here rather than punishing the message being sent now.
            final java.util.List<String> aged = sweepExpired(MlsSendRetentionPolicy.DEFAULT_MAX_AGE_MS);
            if (!aged.isEmpty()) {
                LogUtil.i(TAG, "MlsPendingBodyStore: at the " + MAX_ENTRIES + "-entry cap — swept "
                        + aged.size() + " expired entr(ies) to make room for " + rcsMessageId + ".");
            }
            if (prefs().getAll().size() >= MAX_ENTRIES) {
                // EVICT THE OLDEST rather than declining the newest.
                //
                // Declining was the original choice, reasoning that evicting the oldest "would
                // silently re-open this bug on the messages that have been retrying longest". In
                // practice it inverts: a store full of young-but-unacked entries DECLINES EVERY NEW
                // MESSAGE indefinitely, so nothing recent can be resent at all. Device-observed:
                // a device began a 300-message burst with a full store, declined all 300,
                // and the one message that later failed to decrypt could not be resent — the single
                // loss in an otherwise perfect 600-message bidirectional run.
                //
                // Evicting the oldest bounds memory exactly as before while guaranteeing the NEWEST
                // send always has resend material. The evicted entry is the one that has had the
                // longest chance to be delivered and acked, so it is the cheapest to lose — and it
                // is still protected by the chat-row fallback, which is all the declined message had.
                final String oldest = oldestKey(p, SystemClock.elapsedRealtime());
                if (oldest != null) {
                    p.edit().remove(oldest).commit();
                    LogUtil.w(TAG, "MlsPendingBodyStore: at the " + MAX_ENTRIES + "-entry cap with "
                            + "nothing expirable — evicted the OLDEST (" + oldest + ") to make room "
                            + "for " + rcsMessageId + ". Sends are outrunning their receipts.");
                } else {
                    LogUtil.e(TAG, "MlsPendingBodyStore: at the cap and could not identify an oldest "
                            + "entry — NOT storing " + rcsMessageId + ".");
                    return;
                }
            }
        }
        if (!p.edit().putString(rcsMessageId, encode(SystemClock.elapsedRealtime(), framedBody))
                .commit()) {
            LogUtil.w(TAG, "MlsPendingBodyStore: could not persist " + rcsMessageId);
        }
    }

    /**
     * Separates the fields of a stored value.
     *
     * <p>Not a Base64 character in any alphabet (standard or URL-safe), so it cannot occur in the
     * payload and the three generations of this format are told apart by FIELD COUNT alone.
     */
    private static final String STAMP_SEP = "|";

    /**
     * The current format's leading field.
     *
     * <h2>Three generations, distinguished by field count and nothing else</h2>
     *
     * <ul>
     *   <li><b>1 field</b> {@code <b64>} — pre-retention, no time at all. Expired (below).</li>
     *   <li><b>2 fields</b> {@code <wallMs>|<b64>} — stamped with
     *       {@code System.currentTimeMillis()}. ADOPTED at the current elapsed reading.</li>
     *   <li><b>3 fields</b> {@code 1|<elapsedMs>|<b64>} — stamped with
     *       {@code SystemClock.elapsedRealtime()}, which is what the retention sweep reads.</li>
     * </ul>
     *
     * <p>A version token rather than a bare third field, on {@code MlsRebuildWindowRecord}'s
     * reasoning: reading a WALL stamp as an elapsed one happens to answer conservatively (it looks
     * enormously far in the future, {@code MlsMonotonicAge} calls that a reboot and retains the
     * entry) and being right by coincidence is not a thing to leave load-bearing. The count is what
     * decides; the token is what makes the next change able to say which generation it is looking
     * at.
     */
    private static final String VERSION = "1";

    /** The current format: {@code 1|<elapsedMs>|<b64 payload>}. */
    private static String encode(final long elapsedMs, final byte[] framedBody) {
        return VERSION + STAMP_SEP + elapsedMs + STAMP_SEP
                + Base64.encodeToString(framedBody, Base64.NO_WRAP);
    }

    /**
     * The elapsed stamp in {@code value}, or {@link MlsSendRetentionPolicy#UNSTAMPED} when it has
     * none this clock can read — either a 1-field legacy entry or a 2-field WALL-stamped one.
     *
     * <p>Those two are not the same thing and {@link #sweepExpired} does not treat them the same;
     * {@link #isLegacyWallStamped} is what separates them.
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
     * Whether {@code value} carries a WALL stamp from the previous format — exactly two
     * fields, the first of which parses as a number.
     *
     * <p>Separated from "has no stamp at all" because the two get opposite treatment: an entry that
     * was never timed cannot be aged and must not be immortal, whereas one timed with a clock we no
     * longer trust is ADOPTED. Collapsing them would make the upgrade to this format delete every
     * message in flight across it, which is the same early expiry the elapsed stamp removes — just
     * once, at install time, instead of on every clock jump.
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
     * The key of the entry with the LARGEST PROVABLE AGE, or {@code null} if none can be read.
     *
     * <p><b>Ordered by age, not by stamp.</b> The stamps are
     * {@code elapsedRealtime} readings now, and those are only comparable within one boot: a
     * pre-reboot entry holds a LARGER number than everything written since, so picking the smallest
     * stamp would have called the oldest entry in the store the newest and evicted a message that
     * had just been sent. {@link MlsMonotonicAge#ageMs} answers "the current uptime" for a
     * pre-reboot entry, which is at least every same-boot entry's age, so ordering by it puts them
     * first — which is also what we want on the merits.
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
     * Drop entries older than {@code maxAgeMs} — the bound that replaces "released on the first
     * delivery receipt" for group messages (see {@link MlsSendRetentionPolicy}).
     *
     * @return the ids dropped, so the caller can release their ciphertext in the same sweep
     */
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
                // ADOPT rather than judge. Its stamp is a wall reading, and the whole point of the
                // elapsed stamp is that we do not decide a deletion on one. Re-stamping gives it one full
                // window from a clock that cannot move, which can only RETAIN it longer than the
                // wall clock would have — and it makes it ageable, so it cannot become immortal and
                // wedge the cap the way an un-sweepable entry does.
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
                    + "has a fresh " + (maxAgeMs / 3600000L) + "h window measured with "
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
    public byte[] get(final String rcsMessageId) {
        if (rcsMessageId == null || rcsMessageId.isEmpty()) return null;
        final String v = prefs().getString(rcsMessageId, null);
        if (v == null) return null;
        try {
            final byte[] b = Base64.decode(payloadOf(v), Base64.NO_WRAP);
            return (b == null || b.length == 0) ? null : b;
        } catch (final Throwable t) {
            LogUtil.w(TAG, "MlsPendingBodyStore: corrupt entry for " + rcsMessageId + " — removing");
            prefs().edit().remove(rcsMessageId).commit();
            return null;
        }
    }

    /**
     * Release one message's body — call at a TERMINAL state, alongside the ciphertext release.
     *
     * <p>Terminal means delivered or permanently failed. NOT merely "sent": a sent message can
     * still need a resend, and that resend is exactly what this store serves.
     */
    public void release(final String rcsMessageId) {
        if (rcsMessageId == null || rcsMessageId.isEmpty()) return;
        if (prefs().contains(rcsMessageId)) {
            prefs().edit().remove(rcsMessageId).commit();
        }
    }

    /** Entry count, for diagnostics. */
    public int size() { return prefs().getAll().size(); }
}
