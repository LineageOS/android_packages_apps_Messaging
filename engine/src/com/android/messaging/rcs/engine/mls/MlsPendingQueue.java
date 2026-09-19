/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.engine.mls.MlsAppMessage.Moment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The per-group out-of-order pending-message queue: a deferral buffer that holds only messages it
 * can prove will become processable, so it has no capacity, eviction or TTL. Admission is pure
 * ({@link #admit}); drains take one exact {@code (era, epoch)} bucket; the whole queue persists as
 * one blob. Not thread-safe: guarded by the conversation's lock. See
 * docs/mls/health-and-recovery.md.
 */
public final class MlsPendingQueue {

    /** Why a message was admitted; the three gates mean different things and are kept apart. */
    public enum Admission {
        /** G1: the group is mid-transition; checked before any decrypt is attempted. */
        GROUP_LOCKED,
        /**
         * G2: processing failed with {@code OutOfOrderCommit(29)} or {@code is_from_future}, and
         * the message moment is strictly after the group's, era-major. At or behind is never
         * queued.
         */
        FROM_FUTURE,
        /**
         * G3: the resent-message FTD arm. Compares {@code >=} against
         * {@code SelfHealState.recovered_at}, not the live group moment, because a resend arrives
         * while the group has committed past the recovery. No live caller passes
         * {@code resentMessageForMeFtd = true} yet.
         */
        RESENT_FTD,
        /** Not admitted: every other failure goes to the error/FTD path. */
        NONE;

        public boolean admitted() { return this != NONE; }
    }

    /** G1's mask; delegates so there is one copy (it is not the complement of isDowngraded). */
    public static boolean groupLocked(final int healthStatus) {
        return MlsHealthPredicates.buffersInbound(healthStatus);
    }

    /**
     * The admission predicate, in order. Pure: it decides and does not store.
     *
     * @param healthStatus            the group's persisted health status (a wire number)
     * @param msg                     the message's moment {@code (era, epoch)}, or null if unknown
     * @param group                   the group's current moment; G2's comparand only
     * @param recoveredAt             {@code SelfHealState.recovered_at}, or null if no recovery has
     *                                landed; G3's comparand
     * @param processingFailed        whether the engine failed to process it
     * @param outOfOrderCommit        the engine reported {@code OutOfOrderCommit(29)}
     * @param isFromFuture            the FTD path's own from-the-future flag
     * @param resentMessageForMeFtd   the failure reason was
     *                                {@code CLIENT_FAILURE_RESENT_MESSAGE_FOR_ME_FTD(6)}
     * @param sameApplicationMessageFailing G3's negative condition
     */
    public static Admission admit(final int healthStatus, final Moment msg, final Moment group,
            final Moment recoveredAt,
            final boolean processingFailed, final boolean outOfOrderCommit,
            final boolean isFromFuture, final boolean resentMessageForMeFtd,
            final boolean sameApplicationMessageFailing) {
        // G1 first; a healthy group never takes it.
        if (groupLocked(healthStatus)) {
            return Admission.GROUP_LOCKED;
        }
        // G2: OutOfOrderCommit becomes a self-heal request only when the moment check declines.
        if (processingFailed && (outOfOrderCommit || isFromFuture)
                && strictlyAfter(msg, group)) {
            return Admission.FROM_FUTURE;
        }
        // G3: a different moment source and operator from G2; excluded across the end-MLS family.
        if (resentMessageForMeFtd
                && atOrAfterRecoveredAt(msg, recoveredAt)
                && !sameApplicationMessageFailing
                && !MlsHealthPredicates.isDowngraded(healthStatus)) {
            return Admission.RESENT_FTD;
        }
        return Admission.NONE;
    }

    /** Engine-error ordinal for {@code ExpectedSelfHealToBeOngoing}. */
    public static final int ERROR_EXPECTED_SELF_HEAL_TO_BE_ONGOING = 52;

    /**
     * The one failure that emits nothing (no FTD, receipt or queue entry): error 52 without a
     * same-application-message failure. mls-rs has no producer of it today, so this is unreachable
     * but keeps the rule in one place.
     */
    public static boolean silentDrop(final int errorOrdinal,
            final boolean sameApplicationMessageFailing) {
        return errorOrdinal == ERROR_EXPECTED_SELF_HEAL_TO_BE_ONGOING
                && !sameApplicationMessageFailing;
    }

    /**
     * {@code a > b} with era as the major key, both unsigned: a new era restarts the epoch at 0. A
     * null moment is never after anything.
     */
    public static boolean strictlyAfter(final Moment a, final Moment b) {
        if (a == null || b == null) return false;
        return a.compareTo(b) > 0;
    }

    /**
     * G3's moment gate, {@code msg >= recovered_at}, era-major and unsigned. A null
     * {@code recoveredAt} passes (no recovery has landed, read as {@code (0,0)}); a null
     * {@code msg} refuses.
     */
    public static boolean atOrAfterRecoveredAt(final Moment msg, final Moment recoveredAt) {
        if (msg == null) return false;
        return recoveredAt == null || msg.compareTo(recoveredAt) >= 0;
    }

    /**
     * Whether waiting alone can reach the moment a parked message waits for. An epoch gap we close
     * by replay; an era gap needs a Welcome only a peer or the server can send, so the caller keeps
     * the park and also sends the failure report.
     *
     * @return true if waiting alone can still work
     */
    public static boolean awaitedMomentIsReachable(final Moment msg, final Moment group) {
        // Unknown moments never turn into a report.
        if (msg == null || group == null) return true;
        return msg.era <= group.era;
    }

    /**
     * Whether the ciphertext was sealed in an era the group has left. Classification for logs only:
     * such a message already takes the FTD path and can never be decrypted, since an era advance
     * builds a new group under the same id and purges the prior era's secrets. Compared unsigned.
     */
    public static boolean isFromASupersededEra(final Moment msg, final Moment group) {
        if (msg == null || group == null) return false;
        return Integer.compareUnsigned(msg.era, group.era) < 0;
    }

    // ---- entries ------------------------------------------------------------------------------

    /**
     * The inbound door a parked message came through and must be replayed to. Stored because an
     * application message and a commit are both {@code PrivateMessage}; replaying an application
     * message through the control door decrypts it and discards it.
     */
    public enum Plane {
        /** A control message: commit, proposal, Welcome-bearing frame. */
        CONTROL,
        /** An application ciphertext: a message, receipt or key delivery for the app. */
        APPLICATION;

        public static Plane fromOrdinal(final int i) {
            return i == APPLICATION.ordinal() ? APPLICATION : CONTROL;
        }
    }

    /** One parked message. Immutable. */
    public static final class Entry {
        /** The RCS message id: the dedup key within a bucket, never globally. */
        public final String messageId;
        /** The moment it claims. Its {@code (era, epoch)} is the bucket key. */
        public final Moment moment;
        /** The MLSMessage {@code wire_format}, the variant tag. */
        public final int variantTag;
        /** The MLS group id the message names, or null if it named none. */
        public final byte[] groupId;
        /** The opaque wire blob, replayed verbatim on drain. */
        public final byte[] blob;
        /** Which door it arrived through and must leave through; see {@link Plane}. */
        public final Plane plane;
        /**
         * The E.164 it arrived from, or null if unknown (codec v1/v2). A dropped application entry
         * is reported to this sender; the ciphertext does not name it.
         */
        public final String sender;

        public Entry(final String messageId, final Moment moment, final int variantTag,
                final byte[] groupId, final byte[] blob) {
            this(messageId, moment, variantTag, groupId, blob, Plane.CONTROL);
        }

        public Entry(final String messageId, final Moment moment, final int variantTag,
                final byte[] groupId, final byte[] blob, final Plane plane) {
            this(messageId, moment, variantTag, groupId, blob, plane, null);
        }

        public Entry(final String messageId, final Moment moment, final int variantTag,
                final byte[] groupId, final byte[] blob, final Plane plane,
                final String sender) {
            this.messageId = messageId == null ? "" : messageId;
            this.moment = moment;
            this.variantTag = variantTag;
            this.groupId = groupId;
            this.blob = blob;
            this.plane = plane == null ? Plane.CONTROL : plane;
            this.sender = sender == null || sender.isEmpty() ? null : sender;
        }

        @Override public String toString() {
            return "pending{" + MlsMessageId.forLog(messageId) + " at " + moment + " tag="
                    + variantTag
                    + " " + plane + " " + (blob == null ? 0 : blob.length) + "B}";
        }
    }

    /**
     * Store-time validation outcomes, with the engine-error ordinal. Group-id match (28) is checked
     * before epoch presence (27), which is not the numeric order.
     */
    public enum StoreResult {
        /** Inserted. */
        STORED(0),
        /** Already in this {@code (era, epoch)} bucket; returned as Ok without inserting. */
        DUPLICATE(0),
        /** variant tag ≥ 3 — a Welcome, GroupInfo or KeyPackage cannot be a pending message. */
        INVALID_WIRE_FORMAT(25),
        MISSING_REQUIRED_GROUP_ID(26),
        MISSING_REQUIRED_EPOCH(27),
        INVALID_GROUP_ID(28);

        /** The engine-error ordinal, or 0 for the two non-error outcomes. */
        public final int errorOrdinal;

        StoreResult(final int errorOrdinal) { this.errorOrdinal = errorOrdinal; }

        public boolean stored() { return this == STORED; }
        public boolean isError() { return errorOrdinal != 0; }
    }

    /** The duplicate log line. */
    public static String duplicateLine(final String messageId) {
        return "Pending message already exists for message id: " + MlsMessageId.forLog(messageId);
    }

    // era -> epoch -> entries, in insertion order within a bucket.
    private final TreeMap<Integer, TreeMap<Long, List<Entry>>> mByEra = new TreeMap<>();

    /** The highest variant tag a pending message may carry, exclusive. */
    private static final int MAX_VARIANT_TAG_EXCLUSIVE = 3;

    /**
     * Store one message after the validations, in order.
     *
     * @param expectedGroupId the group this queue belongs to; the message must name it
     */
    public StoreResult store(final Entry e, final byte[] expectedGroupId) {
        if (e == null) return StoreResult.INVALID_WIRE_FORMAT;
        // 1. Variant tag 1 (PublicMessage) or 2 (PrivateMessage): messages to a group. A Welcome,
        // GroupInfo or KeyPackage could never drain from a per-epoch bucket.
        if (e.variantTag <= 0 || e.variantTag >= MAX_VARIANT_TAG_EXCLUSIVE) {
            return StoreResult.INVALID_WIRE_FORMAT;
        }
        // 2. group id present.
        if (e.groupId == null || e.groupId.length == 0) {
            return StoreResult.MISSING_REQUIRED_GROUP_ID;
        }
        // 3. group id matches, before the epoch check.
        if (expectedGroupId == null || !java.util.Arrays.equals(e.groupId, expectedGroupId)) {
            return StoreResult.INVALID_GROUP_ID;
        }
        // 4. epoch present. A null moment means we could not establish one.
        if (e.moment == null) {
            return StoreResult.MISSING_REQUIRED_EPOCH;
        }
        // 5. Dedup within the (era, epoch) bucket only: the same id at another moment is a
        // different delivery attempt.
        final TreeMap<Long, List<Entry>> byEpoch =
                mByEra.computeIfAbsent(e.moment.era, k -> new TreeMap<>());
        final List<Entry> bucket = byEpoch.computeIfAbsent(e.moment.epoch, k -> new ArrayList<>());
        for (final Entry existing : bucket) {
            if (existing.messageId.equals(e.messageId)) {
                return StoreResult.DUPLICATE;
            }
        }
        bucket.add(e);
        return StoreResult.STORED;
    }

    /**
     * Remove and return exactly {@code pending[era][epoch]}, in insertion order. Never a sweep:
     * draining early re-fails and re-buffers on every intermediate commit.
     *
     * @return the taken entries; empty if that bucket is absent
     */
    public List<Entry> take(final int era, final long epoch) {
        final TreeMap<Long, List<Entry>> byEpoch = mByEra.get(era);
        if (byEpoch == null) return Collections.emptyList();
        final List<Entry> taken = byEpoch.remove(epoch);
        if (byEpoch.isEmpty()) mByEra.remove(era);
        return taken == null ? Collections.emptyList() : taken;
    }

    /** {@link #take(int, long)} at a moment. */
    public List<Entry> take(final Moment at) {
        return at == null ? Collections.<Entry>emptyList() : take(at.era, at.epoch);
    }

    /**
     * Everything still parked, era-major, without removing it. The Welcome join reads it before
     * {@link #clear()} so the application entries it loses can be reported.
     */
    public List<Entry> peekAll() {
        final List<Entry> all = new ArrayList<>();
        for (final Map<Long, List<Entry>> byEpoch : mByEra.values()) {
            for (final List<Entry> bucket : byEpoch.values()) all.addAll(bucket);
        }
        return all;
    }

    public int size() {
        int n = 0;
        for (final Map<Long, List<Entry>> byEpoch : mByEra.values()) {
            for (final List<Entry> bucket : byEpoch.values()) n += bucket.size();
        }
        return n;
    }

    public boolean isEmpty() { return size() == 0; }

    /** The eras with parked messages; {@link #takeSupersededEras} prunes the old ones. */
    public java.util.Set<Integer> eras() {
        return Collections.unmodifiableSet(mByEra.keySet());
    }

    /**
     * Remove and return every entry parked in an era before {@code currentEra}, compared unsigned
     * as {@link #isFromASupersededEra} does. Such an entry can never decrypt: an era advance purges
     * the prior era's secrets, and the exact-key drain would never reach it.
     *
     * @return the removed entries, era-major; empty if none
     */
    public List<Entry> takeSupersededEras(final int currentEra) {
        final List<Integer> old = new ArrayList<>();
        for (final int era : eras()) {
            if (Integer.compareUnsigned(era, currentEra) < 0) old.add(era);
        }
        final List<Entry> taken = new ArrayList<>();
        for (final int era : old) {
            for (final List<Entry> bucket : mByEra.remove(era).values()) taken.addAll(bucket);
        }
        return taken;
    }

    /** Drop everything; only for group deletion. */
    public void clear() { mByEra.clear(); }

    // ---- codec --------------------------------------------------------------------------------
    // One blob per conversation, rewritten wholesale: a partial write could make a half-written
    // bucket look drained.

    /**
     * Version 2 appends {@link Entry#plane} after the blob; v1 entries decode as
     * {@link Plane#CONTROL}, since the plane is not recoverable from the bytes. Version 3 appends
     * {@link Entry#sender} ({@code ""} for unknown); earlier entries decode with no sender.
     */
    private static final int CODEC_VERSION = 3;

    /** Serialize the whole queue. */
    public byte[] toBytes() {
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        final java.io.DataOutputStream d = new java.io.DataOutputStream(out);
        try {
            d.writeInt(CODEC_VERSION);
            d.writeInt(size());
            for (final Map.Entry<Integer, TreeMap<Long, List<Entry>>> era : mByEra.entrySet()) {
                for (final Map.Entry<Long, List<Entry>> ep : era.getValue().entrySet()) {
                    for (final Entry e : ep.getValue()) {
                        d.writeUTF(e.messageId);
                        d.writeInt(era.getKey());
                        d.writeLong(ep.getKey());
                        d.writeInt(e.variantTag);
                        writeBytes(d, e.groupId);
                        writeBytes(d, e.blob);
                        d.writeInt(e.plane.ordinal());
                        d.writeUTF(e.sender == null ? "" : e.sender);
                    }
                }
            }
            d.flush();
        } catch (final java.io.IOException impossible) {
            // Returning empty would silently discard the queue.
            throw new IllegalStateException("MlsPendingQueue.toBytes failed", impossible);
        }
        return out.toByteArray();
    }

    /**
     * Deserialize; an empty queue on any malformation, so an unreadable queue costs a resend rather
     * than the conversation. The caller logs the loss.
     */
    public static MlsPendingQueue fromBytes(final byte[] b) {
        final MlsPendingQueue q = new MlsPendingQueue();
        if (b == null || b.length == 0) return q;
        try {
            final java.io.DataInputStream d = new java.io.DataInputStream(
                    new java.io.ByteArrayInputStream(b));
            final int version = d.readInt();
            if (version < 1 || version > CODEC_VERSION) return q;
            final int n = d.readInt();
            if (n < 0) return q;
            for (int i = 0; i < n; i++) {
                final String id = d.readUTF();
                final int era = d.readInt();
                final long epoch = d.readLong();
                final int tag = d.readInt();
                final byte[] gid = readBytes(d);
                final byte[] blob = readBytes(d);
                final Plane plane = version >= 2 ? Plane.fromOrdinal(d.readInt()) : Plane.CONTROL;
                final String sender = version >= 3 ? d.readUTF() : null;
                final Entry e = new Entry(id, new Moment(era, epoch), tag, gid, blob, plane,
                        sender);
                // Not re-validated: the expected group id is not available at decode time.
                q.mByEra.computeIfAbsent(era, k -> new TreeMap<>())
                        .computeIfAbsent(epoch, k -> new ArrayList<>())
                        .add(e);
            }
        } catch (final Throwable malformed) {
            return new MlsPendingQueue();
        }
        return q;
    }

    private static void writeBytes(final java.io.DataOutputStream d, final byte[] v)
            throws java.io.IOException {
        d.writeInt(v == null ? -1 : v.length);
        if (v != null && v.length > 0) d.write(v);
    }

    private static byte[] readBytes(final java.io.DataInputStream d) throws java.io.IOException {
        final int n = d.readInt();
        if (n < 0) return null;
        final byte[] v = new byte[n];
        d.readFully(v);
        return v;
    }

    @Override public String toString() {
        final StringBuilder sb = new StringBuilder("MlsPendingQueue{");
        boolean first = true;
        for (final Map.Entry<Integer, TreeMap<Long, List<Entry>>> era : mByEra.entrySet()) {
            for (final Map.Entry<Long, List<Entry>> ep : era.getValue().entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append("e").append(era.getKey()).append('/').append(ep.getKey())
                  .append('=').append(ep.getValue().size());
            }
        }
        return sb.append('}').toString();
    }

    /** A stable, ordered view for tests: era-major then epoch, buckets in insertion order. */
    public Map<String, List<String>> debugLayout() {
        final Map<String, List<String>> m = new LinkedHashMap<>();
        for (final Map.Entry<Integer, TreeMap<Long, List<Entry>>> era : mByEra.entrySet()) {
            for (final Map.Entry<Long, List<Entry>> ep : era.getValue().entrySet()) {
                final List<String> ids = new ArrayList<>();
                for (final Entry e : ep.getValue()) ids.add(e.messageId);
                m.put(era.getKey() + "/" + ep.getKey(), ids);
            }
        }
        return m;
    }
}
