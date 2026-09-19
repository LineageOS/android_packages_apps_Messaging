/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.e2ee;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import com.android.messaging.rcs.engine.mls.MlsConversationKey;
import com.android.messaging.rcs.engine.mls.MlsMessageId;
import com.android.messaging.rcs.engine.mls.MlsMonotonicAge;
import com.android.messaging.rcs.engine.mls.MlsSealedCacheAccess;
import com.android.messaging.rcs.engine.mls.MlsSealedMessage;
import com.android.messaging.rcs.engine.mls.MlsLog;
import com.android.messaging.rcs.engine.mls.MlsSendRetentionPolicy;
import com.android.messaging.util.LogUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The persisted outbound ciphertext, keyed by {@code rcs_message_id}. A miss costs a sender-ratchet
 * generation and a second ciphertext for one message id on the wire, so nothing here evicts on its
 * own schedule: entries are released at a terminal state, with their conversation, or by the
 * retention sweep. Writes use {@code commit()}, so a process death cannot lose one.
 */
public final class MlsCiphertextCache implements MlsPerConversationState, MlsSealedCacheAccess {

    private static final String TAG = MlsLog.TAG;
    private static final String PREFS = "mls_ciphertext_cache";

    /**
     * Ceiling. At it, expired entries are swept; then only an entry no lifecycle can manage (one
     * with no conversation key, or one provably from before this boot) is evicted; otherwise the
     * new entry is declined. Evicting a manageable oldest entry would re-enable the re-encrypt on
     * the messages retrying longest.
     */
    private static final int MAX_ENTRIES = 256;

    private final Context mCtx;

    public MlsCiphertextCache(final Context ctx) { mCtx = ctx.getApplicationContext(); }

    private SharedPreferences prefs() {
        return mCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * The sealed message for {@code rcsMessageId}, or {@code null}. A caller must replay a non-null
     * result, bytes and headers, rather than encrypt again.
     */
    @Override
    public MlsSealedMessage get(final String rcsMessageId) {
        if (rcsMessageId == null || rcsMessageId.isEmpty()) return null;
        final String v = prefs().getString(rcsMessageId, null);
        if (v == null) return null;
        final MlsSealedMessage m = MlsSealedMessage.decode(rcsMessageId, v);
        if (m == null) {
            LogUtil.w(TAG, "MlsCiphertextCache: corrupt entry for "
                    + MlsMessageId.forLog(rcsMessageId)
                    + " — removing and allowing a re-encrypt");
            prefs().edit().remove(rcsMessageId).commit();
            return null;
        }
        return m.isReplayable() ? m : null;
    }

    /** Store what was sealed, so a repeat send replays it. */
    @Override
    public void put(final MlsSealedMessage sealed) {
        if (sealed == null || sealed.messageId.isEmpty() || !sealed.isReplayable()) return;
        final SharedPreferences p = prefs();
        if (!p.contains(sealed.messageId) && p.getAll().size() >= MAX_ENTRIES) {
            // Sweep before declining: a full store of expired entries would refuse material we
            // need.
            final long nowElapsedMs = SystemClock.elapsedRealtime();
            final int aged = sweepExpired(MlsSendRetentionPolicy.DEFAULT_MAX_AGE_MS, nowElapsedMs);
            if (aged == 0 || p.getAll().size() >= MAX_ENTRIES) {
                // Still full. Evict only an entry the lifecycle cannot manage: an unattributed one
                // (no conversation key, so no release path reaches it) or one provably sealed
                // before this boot (the monotonic sweep cannot age it until a full window of uptime
                // has passed).
                if (evictOldestUnattributed() || evictOldestPreReboot(nowElapsedMs)) {
                    if (!p.edit().putString(sealed.messageId, sealed.encode()).commit()) {
                        LogUtil.w(TAG, "MlsCiphertextCache: could not persist " + MlsMessageId.forLog(sealed.messageId));
                    }
                    return;
                }
                LogUtil.e(TAG, "MlsCiphertextCache: " + MAX_ENTRIES + " entries, " + aged
                        + " sweepable and none unattributed — NOT caching " + MlsMessageId.forLog(sealed.messageId)
                        + ". A retry of it will re-encrypt and burn a generation. Entries are "
                        + "released on delivery/failure and on a conversation's permanent failure, "
                        + "so a persistent ceiling here means one of those paths has stopped "
                        + "running.");
                return;
            }
        }
        if (!p.edit().putString(sealed.messageId, sealed.encode()).commit()) {
            LogUtil.w(TAG, "MlsCiphertextCache: could not persist " + MlsMessageId.forLog(sealed.messageId));
        }
    }

    /**
     * Release one message's ciphertext at a terminal state: delivered (a positive IMDN) or
     * permanently failed. Not at "sent": a resend must replay these exact bytes.
     */
    @Override
    public void release(final String rcsMessageId) {
        if (rcsMessageId == null || rcsMessageId.isEmpty()) return;
        if (prefs().contains(rcsMessageId)) {
            prefs().edit().remove(rcsMessageId).commit();
            LogUtil.i(TAG, "MlsCiphertextCache: released " + MlsMessageId.forLog(rcsMessageId)
                    + " at its terminal");
        } else {
            // Normal for a synthesised group id.
            LogUtil.v(TAG, "MlsCiphertextCache: nothing cached for "
                    + MlsMessageId.forLog(rcsMessageId)
                    + " at its terminal");
        }
    }

    /**
     * Clear the cached ciphertext so the same id may be re-encrypted (RCC.16 §11.1): the peer could
     * not decrypt it. The same effect as {@link #release}, kept separate so the log tells a
     * completion from a negative-IMDN remedy.
     */
    @Override
    public void invalidateForReEncrypt(final String rcsMessageId, final String why) {
        if (rcsMessageId == null || rcsMessageId.isEmpty()) return;
        if (prefs().contains(rcsMessageId)) {
            LogUtil.i(TAG, "MlsCiphertextCache: invalidating " + MlsMessageId.forLog(rcsMessageId)
                    + " for re-encrypt — "
                    + why);
            prefs().edit().remove(rcsMessageId).commit();
        }
    }

    /**
     * Age out entries older than {@code maxAgeMs} by each entry's own elapsed stamp. This is what
     * enforces the 24 h retention for group messages, whose delivery receipt is not terminal (one
     * member confirming says nothing about the others). Ages are provable lower bounds
     * ({@link MlsSendRetentionPolicy#expiredMonotonic}), so an entry may be kept too long, never
     * dropped early.
     *
     * @param nowElapsedMs {@code SystemClock.elapsedRealtime()}, not a wall-clock reading
     * @return how many entries were removed
     */
    @Override
    public int sweepExpired(final long maxAgeMs, final long nowElapsedMs) {
        final SharedPreferences p = prefs();
        final java.util.Map<String, ?> all;
        try {
            all = p.getAll();
        } catch (final Throwable t) {
            LogUtil.w(TAG, "MlsCiphertextCache: could not enumerate for the retention sweep", t);
            return 0;
        }
        final SharedPreferences.Editor e = p.edit();
        int removed = 0;
        int adopted = 0;
        for (final java.util.Map.Entry<String, ?> entry : all.entrySet()) {
            final Object v = entry.getValue();
            if (!(v instanceof String)) continue;
            final MlsSealedMessage m = MlsSealedMessage.decode(entry.getKey(), (String) v);
            // A corrupt entry can never be replayed, so it is sweepable.
            if (m == null) {
                e.remove(entry.getKey());
                removed++;
                continue;
            }
            if (m.sealedAtElapsedMs == MlsSendRetentionPolicy.UNSTAMPED) {
                // Only a wall stamp: adopt it at the current reading. Expiring would drop messages
                // in flight across the upgrade, and leaving it unaged would make it immortal.
                e.putString(entry.getKey(), m.adoptedAt(nowElapsedMs).encode());
                adopted++;
                continue;
            }
            if (MlsSendRetentionPolicy.expiredMonotonic(nowElapsedMs, m.sealedAtElapsedMs,
                    maxAgeMs)) {
                e.remove(entry.getKey());
                removed++;
            }
        }
        if ((removed > 0 || adopted > 0) && e.commit() && adopted > 0) {
            LogUtil.i(TAG, "MlsCiphertextCache: adopted " + adopted + " entr"
                    + (adopted == 1 ? "y" : "ies") + " written before the elapsed stamp existed — "
                    + "each now has a fresh " + (maxAgeMs / 3600000L) + "h window timed with "
                    + "elapsedRealtime, which retains them longer than the wall clock "
                    + "would have, never shorter.");
        }
        if (removed > 0) {
            LogUtil.i(TAG, "MlsCiphertextCache: retention sweep removed " + removed + " entr"
                    + (removed == 1 ? "y" : "ies") + " older than " + maxAgeMs + "ms; "
                    + all.size() + " → " + (all.size() - removed));
        }
        return removed;
    }

    /**
     * Drop the oldest entry that provably predates this boot. {@code rebootedSince} is proof only
     * in the {@code true} direction, so this never evicts an entry that might belong to this boot.
     *
     * @return true if an entry was evicted
     */
    private boolean evictOldestPreReboot(final long nowElapsedMs) {
        final SharedPreferences p = prefs();
        final Map<String, ?> all;
        try {
            all = p.getAll();
        } catch (final Throwable t) {
            return false;
        }
        String oldestKey = null;
        long oldestAge = -1L;
        for (final Map.Entry<String, ?> entry : all.entrySet()) {
            final Object v = entry.getValue();
            if (!(v instanceof String)) continue;
            final MlsSealedMessage m = MlsSealedMessage.decode(entry.getKey(), (String) v);
            if (m == null || m.sealedAtElapsedMs == MlsSendRetentionPolicy.UNSTAMPED) continue;
            if (!MlsMonotonicAge.rebootedSince(m.sealedAtElapsedMs, nowElapsedMs)) continue;
            final long age = MlsMonotonicAge.ageMs(m.sealedAtElapsedMs, nowElapsedMs);
            if (age > oldestAge) {
                oldestAge = age;
                oldestKey = entry.getKey();
            }
        }
        if (oldestKey == null) return false;
        final boolean ok = p.edit().remove(oldestKey).commit();
        if (ok) {
            LogUtil.w(TAG, "MlsCiphertextCache: at the ceiling with nothing sweepable and nothing "
                    + "unattributed — evicted " + MlsConversationKey.forLog(oldestKey) + ", which PROVABLY predates this boot "
                    + ". Its resend material cannot be aged out on a clock we trust "
                    + "until the device has been up for the full window, and declining every send "
                    + "until then is the worse trade.");
        }
        return ok;
    }

    /**
     * Drop the oldest entry with no conversation key. Ordered by the wall stamp {@code sealedAtMs},
     * the only time such an entry has; acceptable because this only picks among entries being
     * evicted anyway.
     *
     * @return true if an entry was evicted
     */
    private boolean evictOldestUnattributed() {
        final SharedPreferences p = prefs();
        final Map<String, ?> all;
        try {
            all = p.getAll();
        } catch (final Throwable t) {
            return false;
        }
        String oldestKey = null;
        long oldestAt = Long.MAX_VALUE;
        for (final Map.Entry<String, ?> entry : all.entrySet()) {
            final Object v = entry.getValue();
            if (!(v instanceof String)) continue;
            final MlsSealedMessage m = MlsSealedMessage.decode(entry.getKey(), (String) v);
            if (m == null || !m.conversationKey.isEmpty()) continue;
            if (m.sealedAtMs < oldestAt) {
                oldestAt = m.sealedAtMs;
                oldestKey = entry.getKey();
            }
        }
        if (oldestKey == null) return false;
        final boolean ok = p.edit().remove(oldestKey).commit();
        if (ok) {
            LogUtil.w(TAG,
                    "MlsCiphertextCache: at the ceiling with nothing sweepable — evicted the "
                    + "oldest UNATTRIBUTED entry " + MlsConversationKey.forLog(oldestKey)
                    + " (pre-dates the conversation "
                    + "stamp, so no release path can ever reach it) to admit a manageable one");
        }
        return ok;
    }

    /**
     * Release every entry sealed for {@code conversationKey}: the permanent-failure arm of the
     * retention policy. The bytes were sealed against group state that no longer exists, so no
     * resend can replay them. An entry with an empty conversation key never matches, or one
     * conversation's terminal would wipe the store.
     *
     * @return how many entries were released
     */
    @Override
    public int releaseConversation(final String conversationKey, final String why) {
        if (conversationKey == null || conversationKey.isEmpty()) return 0;
        final SharedPreferences p = prefs();
        final Map<String, ?> all;
        try {
            all = p.getAll();
        } catch (final Throwable t) {
            LogUtil.w(TAG, "MlsCiphertextCache: could not enumerate to release "
                    + MlsConversationKey.forLog(conversationKey),
                    t);
            return 0;
        }
        final SharedPreferences.Editor e = p.edit();
        int removed = 0;
        for (final Map.Entry<String, ?> entry : all.entrySet()) {
            final Object v = entry.getValue();
            if (!(v instanceof String)) continue;
            final MlsSealedMessage m = MlsSealedMessage.decode(entry.getKey(), (String) v);
            if (m == null) continue;                       // swept elsewhere
            if (!conversationKey.equals(m.conversationKey)) continue;
            e.remove(entry.getKey());
            removed++;
        }
        if (removed > 0 && e.commit()) {
            LogUtil.i(TAG, "MlsCiphertextCache: released " + removed + " entr"
                    + (removed == 1 ? "y" : "ies") + " for "
                    + MlsConversationKey.forLog(conversationKey) + " — " + why
                    + ". These bytes were sealed against group state that no longer exists, so no "
                    + "resend could have replayed them.");
        }
        return removed;
    }

    /**
     * Part of the teardown: entries are keyed per message, but their validity is scoped to the
     * conversation they were sealed in.
     */
    @Override
    public int forgetConversation(final Scope scope) {
        if (scope.canonicalKey == null) return 0;
        return releaseConversation(scope.canonicalKey, "the conversation was forgotten");
    }

    /** Entry count, for diagnostics. */
    @Override
    public int size() { return prefs().getAll().size(); }

    /** Every cached id, for the debug dump. */
    public List<String> ids() {
        final Map<String, ?> all = prefs().getAll();
        return new ArrayList<>(all.keySet());
    }

    /** Drop everything. Used by {@code forget()} and the downgrade path. */
    public void clear() { prefs().edit().clear().commit(); }
}
