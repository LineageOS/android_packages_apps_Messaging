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

import com.android.messaging.rcs.engine.mls.MlsMonotonicAge;
import com.android.messaging.rcs.engine.mls.MlsSealedMessage;
import com.android.messaging.rcs.engine.mls.MlsLog;
import com.android.messaging.rcs.engine.mls.MlsSendRetentionPolicy;
import com.android.messaging.util.LogUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The persisted outbound ciphertext cache (rework item 7.1, invariant 62).
 *
 * <p>Keyed by the app's {@code rcs_message_id}, which only became possible once the wire id and the
 * app id were unified — before that a cache keyed by message id could not be
 * looked up from either side.
 *
 * <p><b>Not a cache in the discardable sense.</b> A miss does not cost latency, it costs a
 * generation from the sender ratchet and puts a second ciphertext for one message id on the wire.
 * So nothing here evicts on its own schedule; entries are released when the message reaches a
 * terminal state.
 *
 * <p>SharedPreferences-backed with synchronous {@code commit()}, matching {@code MlsRecordStore} and
 * {@code MlsRendezvousStore}. Same reason as the rendezvous table: the window being closed includes
 * "the process died", and an {@code apply()} leaves the write inside it.
 */
public final class MlsCiphertextCache implements MlsPerConversationState {

    // MlsLog.TAG ("RcsMls"), NOT LogUtil.BUGLE_TAG. Every other line in the MLS flow logs
    // under RcsMls, and a store that logs elsewhere is invisible to anyone grepping the flow —
    // which cost a debugging round when this store's lines were the only ones a MessagingApp
    // filter showed and the transport's were the ones that mattered.
    private static final String TAG = MlsLog.TAG;
    private static final String PREFS = "mls_ciphertext_cache";

    /**
     * A ceiling so an undelivered backlog cannot grow the file without bound.
     *
     * <p>On reaching it we log and DECLINE to store rather than evicting. Evicting the oldest would
     * silently re-enable the re-encrypt this class exists to prevent, on exactly the messages that
     * have been retrying longest — the ones where it matters most.
     */
    private static final int MAX_ENTRIES = 256;

    private final Context mCtx;

    public MlsCiphertextCache(final Context ctx) { mCtx = ctx.getApplicationContext(); }

    private SharedPreferences prefs() {
        return mCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * The sealed message for {@code rcsMessageId}, or {@code null}.
     *
     * <p>Callers must REPLAY a non-null result — both its bytes and its headers — rather than
     * encrypting again.
     */
    public MlsSealedMessage get(final String rcsMessageId) {
        if (rcsMessageId == null || rcsMessageId.isEmpty()) return null;
        final String v = prefs().getString(rcsMessageId, null);
        if (v == null) return null;
        final MlsSealedMessage m = MlsSealedMessage.decode(rcsMessageId, v);
        if (m == null) {
            LogUtil.w(TAG, "MlsCiphertextCache: corrupt entry for " + rcsMessageId
                    + " — removing and allowing a re-encrypt");
            prefs().edit().remove(rcsMessageId).commit();
            return null;
        }
        return m.isReplayable() ? m : null;
    }

    /** Store what was sealed, so a repeat send replays it. */
    public void put(final MlsSealedMessage sealed) {
        if (sealed == null || sealed.messageId.isEmpty() || !sealed.isReplayable()) return;
        final SharedPreferences p = prefs();
        if (!p.contains(sealed.messageId) && p.getAll().size() >= MAX_ENTRIES) {
            // SWEEP BEFORE DECLINING (and the same lesson MlsPendingBodyStore learned).
            // Declining while the store is full of entries that are past their retention window is
            // the worst of both: we keep material nobody can use AND refuse material we need. The
            // sweep is cheap and only runs at the ceiling.
            final long nowElapsedMs = SystemClock.elapsedRealtime();
            final int aged = sweepExpired(MlsSendRetentionPolicy.DEFAULT_MAX_AGE_MS, nowElapsedMs);
            if (aged == 0 || p.getAll().size() >= MAX_ENTRIES) {
                // STILL FULL. Before declining, evict the oldest UNATTRIBUTED entry — and ONLY an
                // unattributed one.
                //
                // The class contract says we decline rather than evict, and for a normal entry that
                // is right: evicting the oldest re-enables the re-encrypt on exactly the messages
                // that have been retrying longest. That argument depends on the entry being
                // MANAGEABLE — releasable at its terminal, releasable with its conversation, and
                // sweepable on its own clock.
                //
                // A row written before the conversation stamp existed is none of those. It cannot be
                // released by conversation because it does not know its own, so after an upgrade a
                // store full of them blocks every new send for up to the full retention window with
                // no mechanism able to clear it. Between preserving an entry the lifecycle cannot
                // manage and admitting one it can, admitting wins — and the eviction is bounded,
                // because nothing writes unattributed entries any more.
                //
                // AND A SECOND ARM, for the price the monotonic sweep charges. Retention is
                // aged with elapsedRealtime now, and nothing written before a reboot can be shown
                // older than the current uptime — so a store sitting at the ceiling when the device
                // reboots cannot be relieved by the sweep for up to a full window OF UPTIME, and
                // every send in that stretch is uncacheable. That is precisely the device-observed
                // failure of a store pegged at 256 with "a retry of it will re-encrypt", arriving
                // by a different door.
                //
                // A pre-reboot entry is the right one to give up, and by the conservative argument
                // rather than in spite of it: that claim is that eviction must not hit an entry the
                // lifecycle can still manage, and this one has had a whole process lifetime AND a
                // reboot to be released and was not. It is also the entry with the longest chance to
                // have been delivered and acked, which is the reason MlsPendingBodyStore gives for
                // evicting its own oldest. The arm is bounded twice: it fires only at the ceiling,
                // and only on an entry MlsMonotonicAge.rebootedSince can PROVE predates this boot.
                if (evictOldestUnattributed() || evictOldestPreReboot(nowElapsedMs)) {
                    if (!p.edit().putString(sealed.messageId, sealed.encode()).commit()) {
                        LogUtil.w(TAG, "MlsCiphertextCache: could not persist " + sealed.messageId);
                    }
                    return;
                }
                LogUtil.e(TAG, "MlsCiphertextCache: " + MAX_ENTRIES + " entries, " + aged
                        + " sweepable and none unattributed — NOT caching " + sealed.messageId
                        + ". A retry of it will re-encrypt and burn a generation. Entries are "
                        + "released on delivery/failure and on a conversation's permanent failure, "
                        + "so a persistent ceiling here means one of those paths has stopped "
                        + "running.");
                return;
            }
        }
        if (!p.edit().putString(sealed.messageId, sealed.encode()).commit()) {
            LogUtil.w(TAG, "MlsCiphertextCache: could not persist " + sealed.messageId);
        }
    }

    /**
     * Release one message's ciphertext — call when it reaches a TERMINAL state.
     *
     * <p>Terminal means delivered (a positive IMDN), or permanently failed. NOT "sent": a sent
     * message can still need a resend, and that resend must replay these exact bytes.
     */
    public void release(final String rcsMessageId) {
        if (rcsMessageId == null || rcsMessageId.isEmpty()) return;
        if (prefs().contains(rcsMessageId)) {
            prefs().edit().remove(rcsMessageId).commit();
            // SILENT SUCCESS WAS THE PROBLEM. This method logged nothing, so a store full of
            // outstanding entries was indistinguishable from a broken release path — and it was
            // misread as exactly that during an audit, before the two stores were compared
            // against each other. One line per terminal is cheap and makes the lifecycle readable
            // off a device.
            LogUtil.i(TAG, "MlsCiphertextCache: released " + rcsMessageId + " at its terminal");
        } else {
            // NOT AN ERROR, and worth saying so: a release for an id we never cached is the normal
            // case for a synthesised group id, and for any send made before this store existed.
            LogUtil.v(TAG, "MlsCiphertextCache: nothing cached for " + rcsMessageId
                    + " at its terminal");
        }
    }

    /**
     * Clear the cached ciphertext so the SAME id may be re-encrypted (§11.1a path 3).
     *
     * <p>Distinct from {@link #release} in intent even though the effect is identical, and the
     * distinction is worth keeping at the call site: release means "this message is finished",
     * whereas this means "the peer told us it could not decrypt, so the cached bytes are known-bad
     * and the same id must be re-encrypted at the repaired state". Collapsing them loses the ability
     * to tell a normal completion from a negative-IMDN remedy in a log.
     */
    public void invalidateForReEncrypt(final String rcsMessageId, final String why) {
        if (rcsMessageId == null || rcsMessageId.isEmpty()) return;
        if (prefs().contains(rcsMessageId)) {
            LogUtil.i(TAG, "MlsCiphertextCache: invalidating " + rcsMessageId + " for re-encrypt — "
                    + why);
            prefs().edit().remove(rcsMessageId).commit();
        }
    }

    /**
     * Age out entries older than {@code maxAgeMs}, using each entry's OWN {@code sealedAtMs}.
     *
     * <h2>Why this has to exist separately</h2>
     *
     * <p>Retention used to run entirely through {@code MlsPendingBodyStore.sweepExpired}, whose
     * returned ids were then released here. That releases only entries whose PENDING BODY is still
     * present and expiring — so any sealed entry whose body was released earlier, or never stored,
     * was immortal. Nothing else ever aged this store, and {@link #put} declines rather than evicts,
     * so the orphans accumulated to {@link #MAX_ENTRIES} and then every subsequent send logged
     * <i>"the release path has stopped running"</i> and cached nothing at all.
     *
     * <p>Device-observed under a full-mesh stress: the store sat pegged at 256
     * and every outbound message from that point on was uncacheable, meaning any retry would
     * re-encrypt and burn a generation — the exact outcome invariant 62 exists to prevent.
     *
     * <p>Group messages are the ones that get here: a group's delivery receipt is deliberately NOT
     * terminal (one member confirming says nothing about the others), so their material is retained
     * "until permanent failure or 24h" — and this is the only thing that implements the 24h.
     *
     * <h2>The clock is {@code elapsedRealtime}, and that is load-bearing</h2>
     *
     * <p>This aged entries on {@code sealedAtMs} against {@code System.currentTimeMillis()}. A
     * forward jump of the wall clock — an NTP correction after a boot with a dead RTC is one of
     * arbitrary size — made every entry look old and deleted the replay material for messages that
     * were still in flight, logging only that entries had aged out. {@code nowElapsedMs} is a
     * reading {@code settimeofday} cannot touch and
     * {@link MlsSendRetentionPolicy#expiredMonotonic} answers only ages it can PROVE, so an entry
     * can now be retained too long and never dropped early.
     *
     * @param nowElapsedMs {@code SystemClock.elapsedRealtime()}, NOT a wall-clock reading
     * @return how many entries were removed
     */
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
            // A CORRUPT ENTRY IS SWEEPABLE. It can never be replayed — get() already discards it —
            // so keeping it only consumes a slot that a replayable message needs.
            if (m == null) {
                e.remove(entry.getKey());
                removed++;
                continue;
            }
            if (m.sealedAtElapsedMs == MlsSendRetentionPolicy.UNSTAMPED) {
                // A ROW FROM BEFORE THE ELAPSED STAMP EXISTED. Its only time is a wall stamp, which
                // is exactly what we have stopped deciding deletions on, so it is ADOPTED at the
                // current reading and ages from here. Expiring it instead would make the upgrade to
                // this format delete every message in flight across it — the same early expiry
                // this class exists to prevent, once, at install time. Retaining it untouched would
                // make it immortal, which wedges the ceiling. Adopting is bounded and errs long.
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
                    + "each now has a fresh " + (maxAgeMs / 3600000L) + "h window measured with "
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
     * Drop the oldest entry that demonstrably predates this boot, if there is one.
     *
     * <p>The ceiling's second-to-last resort, below {@link #evictOldestUnattributed} and above
     * declining. It exists because the monotonic retention sweep cannot prove anything written
     * before a reboot is older than the current uptime, so a full store that survives a reboot has
     * nothing sweepable for up to a whole window of uptime — during which every send is uncacheable
     * and every retry re-encrypts and burns a generation. See the reasoning at the call site for why
     * a pre-reboot entry is the right one to give up.
     *
     * <p>{@code rebootedSince} is ONE-DIRECTIONAL: {@code true} is proof, because
     * {@code elapsedRealtime} cannot go backwards within a boot. So this arm never fires on an entry
     * that might belong to this boot, and a reboot it fails to detect simply leaves this arm
     * declining — the conservative direction.
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
                    + "unattributed — evicted " + oldestKey + ", which PROVABLY predates this boot "
                    + ". Its resend material cannot be aged out on a clock we trust "
                    + "until the device has been up for the full window, and declining every send "
                    + "until then is the worse trade.");
        }
        return ok;
    }

    /**
     * Drop the oldest entry that carries NO conversation key, if there is one.
     *
     * <p>Only ever called at the ceiling, and deliberately never touches a stamped entry — see the
     * reasoning at the call site. Oldest by {@code sealedAtMs} so the one closest to ageing out
     * anyway goes first.
     *
     * <p><b>{@code sealedAtMs} is the right key HERE and nowhere else in this
     * class.</b> An unattributed row predates the conversation stamp, so it also predates
     * {@code sealedAtElapsedMs} and has no elapsed reading to order by; a wall stamp is all there
     * is. That is tolerable because this is a TIE-BREAK among rows that are all being evicted on
     * the same grounds — picking the wrong one costs one slot, not a deletion — whereas
     * {@link #sweepExpired} was deciding whether to delete AT ALL, which is the difference between
     * an ordering and a verdict.
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
            LogUtil.w(TAG, "MlsCiphertextCache: at the ceiling with nothing sweepable — evicted the "
                    + "oldest UNATTRIBUTED entry " + oldestKey + " (pre-dates the conversation "
                    + "stamp, so no release path can ever reach it) to admit a manageable one");
        }
        return ok;
    }

    /**
     * Release every entry sealed for {@code conversationKey} — the PERMANENT-FAILURE arm.
     *
     * <h2>The arm that had no caller</h2>
     *
     * <p>{@code MlsSendRetentionPolicy} retains undelivered material until <i>permanent failure OR
     * 24h</i>. Nothing ever declared permanent failure, so the first arm was unreachable and every
     * undeliverable message waited out the full day — two arms, one dead, the same shape as any
     * other terminal state with no exit edge.
     *
     * <p>It matters because the material is not merely stale, it is <b>unusable</b>: once a
     * conversation has been torn down or has reached a declared terminal, the group those bytes were
     * sealed against no longer exists, so no resend can ever replay them. Holding them for 24 hours
     * consumes the ceiling that working conversations need — device-observed, pegged at
     * {@link #MAX_ENTRIES} with every subsequent send logging "a retry will re-encrypt".
     *
     * <p>An entry with an EMPTY conversation key is never matched. Those are rows from a build
     * before the key was recorded, and treating empty as a wildcard would make one conversation's
     * terminal wipe the whole store.
     *
     * @return how many entries were released
     */
    public int releaseConversation(final String conversationKey, final String why) {
        if (conversationKey == null || conversationKey.isEmpty()) return 0;
        final SharedPreferences p = prefs();
        final Map<String, ?> all;
        try {
            all = p.getAll();
        } catch (final Throwable t) {
            LogUtil.w(TAG, "MlsCiphertextCache: could not enumerate to release " + conversationKey, t);
            return 0;
        }
        final SharedPreferences.Editor e = p.edit();
        int removed = 0;
        for (final Map.Entry<String, ?> entry : all.entrySet()) {
            final Object v = entry.getValue();
            if (!(v instanceof String)) continue;
            final MlsSealedMessage m = MlsSealedMessage.decode(entry.getKey(), (String) v);
            if (m == null) continue;                       // swept elsewhere; not ours to judge here
            if (!conversationKey.equals(m.conversationKey)) continue;
            e.remove(entry.getKey());
            removed++;
        }
        if (removed > 0 && e.commit()) {
            LogUtil.i(TAG, "MlsCiphertextCache: released " + removed + " entr"
                    + (removed == 1 ? "y" : "ies") + " for " + conversationKey + " — " + why
                    + ". These bytes were sealed against group state that no longer exists, so no "
                    + "resend could have replayed them.");
        }
        return removed;
    }

    /**
     * Teardown. A forgotten conversation's sealed bytes cannot be replayed —
     * the group they were sealed against is gone — so they are debris that occupies the ceiling.
     *
     * <p>This class used to be EXEMPT from the teardown registry on the grounds that it is keyed by
     * {@code rcsMessageId} and therefore per-message. The key is still per-message; the exemption
     * was wrong anyway, because per-message state whose VALIDITY is scoped to a conversation dies
     * with that conversation. Recording the conversation on each entry is what makes this
     * expressible.
     */
    @Override
    public int forgetConversation(final Scope scope) {
        if (scope.canonicalKey == null) return 0;
        return releaseConversation(scope.canonicalKey, "the conversation was forgotten");
    }

    /** Entry count, for diagnostics. */
    public int size() { return prefs().getAll().size(); }

    /** Every cached id, for the debug dump. */
    public List<String> ids() {
        final Map<String, ?> all = prefs().getAll();
        return new ArrayList<>(all.keySet());
    }

    /** Drop everything. Used by {@code forget()} and the downgrade path. */
    public void clear() { prefs().edit().clear().commit(); }
}
