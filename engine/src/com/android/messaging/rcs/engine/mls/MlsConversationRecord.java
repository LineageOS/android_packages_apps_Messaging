/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.engine.mls.MlsAppMessage.Moment;
import com.android.messaging.rcs.log.LogMask;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The one persisted record per {@code (identity, groupId)}, which an era advance keeps. Immutable
 * and written whole ({@link #encode()} and {@link #decode} are the only writers); never indexed;
 * retired field numbers are never reused. See docs/mls/group-lifecycle.md.
 */
public final class MlsConversationRecord {

    /** Bump only for a change the decoder cannot absorb by skipping unknown fields. */
    private static final int VERSION = 2;

    /** Unframed, read-only: refusing it would reset every existing conversation to Unknown. */
    private static final int VERSION_UNFRAMED = 1;

    /**
     * {@link #epochAuthEra} when no era is known. Only {@code -1}: eras are unsigned, so treating
     * any negative value as unknown would misclassify every era above 2^31.
     */
    public static final int ERA_UNKNOWN = -1;

    // Field numbers. Reserved, never reused: 18 (pending queues).
    private static final int F_HEALTH_STATUS = 1;
    private static final int F_SELF_HEAL_KIND = 2;
    private static final int F_PENDING_OP = 3;
    private static final int F_MOMENT = 8;
    private static final int F_LAST_HEALTHY_MOMENT = 9;
    private static final int F_RECOVERED_AT = 25;
    private static final int F_SELF_HEAL_BUDGET = 10;
    private static final int F_STORED_STATUS_REQUEST = 11;
    private static final int F_EXPECTED_COMMIT_KIND = 12;
    private static final int F_MEMBERSHIP_HISTORY = 14;
    private static final int F_EPOCH_AUTHENTICATORS = 15;
    private static final int F_MEMBER_VALIDITY = 16;
    private static final int F_LAST_PARTICIPANT_KEY_UPDATE = 17;
    private static final int F_IDENTITY = 20;
    private static final int F_GROUP_ID = 21;
    private static final int F_RCS_GROUP_ID = 22;
    private static final int F_PEER_E164 = 23;
    private static final int F_SENDS_THIS_EPOCH = 24;
    private static final int F_FTD_RESEND_COUNTS = 26;
    private static final int F_SENDS_SINCE_LEAF_ROTATION = 27;
    private static final int F_SELF_LEFT_AT = 28;
    private static final int F_EPOCH_AUTH_ERA = 29;
    private static final int F_CONTINUITY_TOKEN = 13;

    /** What commit we are waiting to see come back. */
    public enum ExpectedCommitKind {
        NONE(0), SELF_KEY_UPDATE(1), REQUEST_METADATA_KEYS(2);

        public final int wire;

        ExpectedCommitKind(final int wire) { this.wire = wire; }

        public static ExpectedCommitKind fromWire(final int w) {
            for (final ExpectedCommitKind k : values()) if (k.wire == w) return k;
            return NONE;
        }
    }

    /** The self-heal limiter; separate from {@link MlsPendingOperation#attemptCount}. */
    public static final class SelfHealBudget {
        public final int retryCount;
        public final long firstAttemptAtMs;
        /** A heal has run since the last clear. */
        public final boolean healedSinceClear;

        public SelfHealBudget(final int retryCount, final long firstAttemptAtMs,
                final boolean healedSinceClear) {
            this.retryCount = Math.max(0, retryCount);
            this.firstAttemptAtMs = firstAttemptAtMs;
            this.healedSinceClear = healedSinceClear;
        }

        public static final SelfHealBudget EMPTY = new SelfHealBudget(0, 0L, false);

        public SelfHealBudget counted(final long nowMs) {
            return new SelfHealBudget(retryCount + 1,
                    firstAttemptAtMs == 0L ? nowMs : firstAttemptAtMs, true);
        }

        /** An elapsed window starts a new one ({@link #rolled}); it does not end healing. */
        public boolean windowElapsed(final long nowMs, final long windowMs) {
            return windowMs > 0L && firstAttemptAtMs > 0L && (nowMs - firstAttemptAtMs) >= windowMs;
        }

        public SelfHealBudget rolled() {
            return new SelfHealBudget(0, 0L, healedSinceClear);
        }

        /** An elapsed window is not exhaustion: the window rolls. */
        public boolean exhausted(final int limit, final long nowMs, final long windowMs) {
            if (windowElapsed(nowMs, windowMs)) return false;
            return limit > 0 && retryCount >= limit;
        }

        @Override public String toString() {
            return "budget{n=" + retryCount + " since=" + firstAttemptAtMs
                    + (healedSinceClear ? " healed" : "") + "}";
        }
    }

    /** A requested health status not yet acted on; the newer request wins, not the severer. */
    public static final class StatusRequest {
        public final int requestedStatus;
        public final int cause;

        public StatusRequest(final int requestedStatus, final int cause) {
            this.requestedStatus = requestedStatus;
            this.cause = cause;
        }

        @Override public String toString() {
            return "req{" + MlsHealthStates.name(requestedStatus) + " cause=" + cause + "}";
        }
    }

    /** A member's certificate validity window. */
    public static final class MemberValidity {
        public final long notBeforeSecs;
        public final long notAfterSecs;

        public MemberValidity(final long notBeforeSecs, final long notAfterSecs) {
            this.notBeforeSecs = notBeforeSecs;
            this.notAfterSecs = notAfterSecs;
        }

        public boolean expiresWithin(final long nowSecs, final long horizonSecs) {
            return notAfterSecs > 0L && (notAfterSecs - nowSecs) < horizonSecs;
        }
    }

    /** Self MSISDN, half of the key. */
    public final String identity;
    public final byte[] groupId;
    public final String rcsGroupId;
    public final String peerE164;

    /** One of {@link MlsHealthStates}. */
    public final int healthStatus;
    /** Not derivable from the health status. */
    public final MlsSelfHealKind selfHealKind;
    /** Null means nothing is in flight. */
    public final MlsPendingOperation pendingOperation;
    public final Moment moment;
    /** Distinguishes "we are behind" from "the fetched info is stale". */
    public final Moment lastHealthyMoment;
    /** Stamped only on reaching Healthy from a recovery state, so the last repair stays visible. */
    public final Moment recoveredAt;
    public final SelfHealBudget selfHealBudget;
    /** Nullable. */
    public final StatusRequest storedStatusRequest;
    public final ExpectedCommitKind expectedCommitKind;
    /** Era → epoch → members: a past-epoch message is validated against that epoch's members. */
    public final Map<Integer, Map<Long, String[]>> membershipHistory;
    /**
     * Epoch → authenticator for the one era {@link #epochAuthEra}; writing a new era clears it,
     * unlike {@link #membershipHistory}, which spans eras.
     */
    public final Map<Long, byte[]> epochAuthenticators;

    /**
     * Or {@link #ERA_UNKNOWN}: such a map may mix eras and is not history, but its current-epoch
     * entry is correct, so it is cleared on the next write rather than at decode.
     */
    public final int epochAuthEra;
    /** Leaf index → validity window. */
    public final Map<Integer, MemberValidity> memberValidity;
    /** MSISDN → last participant-key update, ms. */
    public final Map<String, Long> lastParticipantKeyUpdate;

    /**
     * FTDs we reported per message id, durable so a restart cannot reset the RCC.16 §10.3 cap. Not
     * the count peers report to us, which stays in memory.
     */
    public final Map<String, Integer> ftdResendCounts;

    public static final int MAX_FTD_RESEND_ENTRIES = 32;

    /** App sends since the last epoch change: the application ratchet generation. */
    public final int sendsThisEpoch;

    /**
     * App sends since our own leaf last rotated, the rekey input. Peer and add-only commits change
     * the epoch without rotating our leaf.
     */
    public final int sendsSinceLeafRotation;

    /** When we proposed our own removal (RCC.16 §9.4), wall-clock ms, or 0. Not a health state. */
    public final long selfLeftAtMs;

    public boolean selfLeft() { return selfLeftAtMs > 0L; }

    /**
     * The decoded RCC.16 §7.11.12.1 continuity token, or empty. A secret: never log it, and never
     * put it in a server-bound GroupInfo.
     */
    public final byte[] continuityToken;

    public boolean hasContinuityToken() { return continuityToken.length > 0; }

    private MlsConversationRecord(final Builder b) {
        identity = b.identity == null ? "" : b.identity;
        groupId = b.groupId == null ? new byte[0] : b.groupId;
        rcsGroupId = b.rcsGroupId == null ? "" : b.rcsGroupId;
        peerE164 = b.peerE164 == null ? "" : b.peerE164;
        healthStatus = b.healthStatus;
        selfHealKind = b.selfHealKind == null ? MlsSelfHealKind.NONE : b.selfHealKind;
        pendingOperation = b.pendingOperation;
        moment = b.moment;
        lastHealthyMoment = b.lastHealthyMoment;
        recoveredAt = b.recoveredAt;
        selfHealBudget = b.selfHealBudget == null ? SelfHealBudget.EMPTY : b.selfHealBudget;
        storedStatusRequest = b.storedStatusRequest;
        expectedCommitKind = b.expectedCommitKind == null
                ? ExpectedCommitKind.NONE : b.expectedCommitKind;
        membershipHistory = Collections.unmodifiableMap(new TreeMap<>(b.membershipHistory));
        epochAuthenticators = Collections.unmodifiableMap(new TreeMap<>(b.epochAuthenticators));
        // Verbatim: a negative int is an era above 2^31.
        epochAuthEra = b.epochAuthEra;
        memberValidity = Collections.unmodifiableMap(new TreeMap<>(b.memberValidity));
        lastParticipantKeyUpdate =
                Collections.unmodifiableMap(new LinkedHashMap<>(b.lastParticipantKeyUpdate));
        ftdResendCounts = Collections.unmodifiableMap(new LinkedHashMap<>(b.ftdResendCounts));
        sendsThisEpoch = b.sendsThisEpoch;
        sendsSinceLeafRotation = b.sendsSinceLeafRotation;
        selfLeftAtMs = Math.max(0L, b.selfLeftAtMs);
        continuityToken = b.continuityToken == null ? new byte[0] : b.continuityToken;
    }

    /** Identity first, because the engine is per-identity. */
    public static String key(final String identity, final byte[] groupId) {
        return (identity == null ? "" : identity) + "/" + hex(groupId);
    }

    public String key() { return key(identity, groupId); }

    /**
     * The key of the alias row that maps a conversation key to a group id. An E.164 cannot contain
     * NUL, so the prefix is unambiguous.
     */
    public static String aliasKey(final String identity, final String conversationId) {
        return (identity == null ? "" : identity) + "\u0000" + conversationId;
    }

    /**
     * The alias rows (present ones only) to drop when a conversation is forgotten, selected by
     * group id first because a teardown cannot be sure which key space the writer used. Rows for
     * other identities are never touched.
     */
    public static List<String> aliasKeysToForget(final Map<String, ?> aliasRows,
            final String identity, final String encodedGroupId, final String... conversationKeys) {
        final List<String> out = new ArrayList<>();
        if (aliasRows == null || aliasRows.isEmpty()) return out;
        final Set<String> seen = new LinkedHashSet<>();
        final String prefix = aliasKey(identity, "");
        if (encodedGroupId != null && !encodedGroupId.isEmpty()) {
            // A non-String value is a row somebody else wrote.
            for (final Map.Entry<String, ?> e : aliasRows.entrySet()) {
                if (e.getKey() == null || !e.getKey().startsWith(prefix)) continue;
                if (encodedGroupId.equals(e.getValue()) && seen.add(e.getKey())) {
                    out.add(e.getKey());
                }
            }
        }
        if (conversationKeys != null) {
            for (final String c : conversationKeys) {
                if (c == null || c.isEmpty()) continue;
                final String k = aliasKey(identity, c);
                if (aliasRows.containsKey(k) && seen.add(k)) out.add(k);
            }
        }
        return out;
    }

    /** A brand-new record for a group that exists but has never had a status written. */
    public static MlsConversationRecord initial(final String identity, final byte[] groupId,
            final String rcsGroupId, final String peerE164) {
        return builder()
                .identity(identity).groupId(groupId).rcsGroupId(rcsGroupId).peerE164(peerE164)
                .healthStatus(MlsHealthStates.UNKNOWN)
                .build();
    }

    public Builder toBuilder() {
        final Builder b = new Builder();
        b.identity = identity;
        b.groupId = groupId;
        b.rcsGroupId = rcsGroupId;
        b.peerE164 = peerE164;
        b.healthStatus = healthStatus;
        b.selfHealKind = selfHealKind;
        b.pendingOperation = pendingOperation;
        b.moment = moment;
        b.lastHealthyMoment = lastHealthyMoment;
        b.recoveredAt = recoveredAt;
        b.selfHealBudget = selfHealBudget;
        b.storedStatusRequest = storedStatusRequest;
        b.expectedCommitKind = expectedCommitKind;
        b.membershipHistory.putAll(membershipHistory);
        b.epochAuthenticators.putAll(epochAuthenticators);
        b.epochAuthEra = epochAuthEra;
        b.memberValidity.putAll(memberValidity);
        b.lastParticipantKeyUpdate.putAll(lastParticipantKeyUpdate);
        b.ftdResendCounts.putAll(ftdResendCounts);
        b.sendsThisEpoch = sendsThisEpoch;
        b.sendsSinceLeafRotation = sendsSinceLeafRotation;
        b.selfLeftAtMs = selfLeftAtMs;
        b.continuityToken = continuityToken;
        return b;
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String identity;
        private byte[] groupId;
        private String rcsGroupId;
        private String peerE164;
        private int healthStatus = MlsHealthStates.UNKNOWN;
        private MlsSelfHealKind selfHealKind = MlsSelfHealKind.NONE;
        private MlsPendingOperation pendingOperation;
        private Moment moment;
        private Moment lastHealthyMoment;
        private Moment recoveredAt;
        private SelfHealBudget selfHealBudget = SelfHealBudget.EMPTY;
        private StatusRequest storedStatusRequest;
        private ExpectedCommitKind expectedCommitKind = ExpectedCommitKind.NONE;
        private final Map<Integer, Map<Long, String[]>> membershipHistory = new TreeMap<>();
        private final Map<Long, byte[]> epochAuthenticators = new TreeMap<>();
        private int epochAuthEra = ERA_UNKNOWN;
        private final Map<Integer, MemberValidity> memberValidity = new TreeMap<>();
        private final Map<String, Long> lastParticipantKeyUpdate = new LinkedHashMap<>();
        private final Map<String, Integer> ftdResendCounts = new LinkedHashMap<>();
        private int sendsThisEpoch;
        private int sendsSinceLeafRotation;
        private long selfLeftAtMs;
        private byte[] continuityToken;

        public Builder identity(final String v) { identity = v; return this; }
        public Builder groupId(final byte[] v) { groupId = v; return this; }
        public Builder rcsGroupId(final String v) { rcsGroupId = v; return this; }
        public Builder peerE164(final String v) { peerE164 = v; return this; }
        public Builder healthStatus(final int v) { healthStatus = v; return this; }
        public Builder selfHealKind(final MlsSelfHealKind v) { selfHealKind = v; return this; }
        public Builder pendingOperation(final MlsPendingOperation v) {
            pendingOperation = v; return this;
        }
        public Builder moment(final Moment v) { moment = v; return this; }
        public Builder lastHealthyMoment(final Moment v) { lastHealthyMoment = v; return this; }
        public Builder recoveredAt(final Moment v) { recoveredAt = v; return this; }
        public Builder selfHealBudget(final SelfHealBudget v) { selfHealBudget = v; return this; }
        public Builder storedStatusRequest(final StatusRequest v) {
            storedStatusRequest = v; return this;
        }
        public Builder expectedCommitKind(final ExpectedCommitKind v) {
            expectedCommitKind = v; return this;
        }
        /** A different era clears the map first. */
        public Builder putEpochAuthenticator(final int era, final long epoch, final byte[] auth) {
            if (era != epochAuthEra) {
                epochAuthenticators.clear();
                epochAuthEra = era;
            }
            epochAuthenticators.put(epoch, auth == null ? new byte[0] : auth);
            trimOldest(epochAuthenticators, EPOCH_AUTH_RETENTION);
            return this;
        }

        /** The decoder's path; not era-aware, since the era field is decoded after the entries. */
        Builder restoreEpochAuthenticator(final long epoch, final byte[] auth) {
            epochAuthenticators.put(epoch, auth == null ? new byte[0] : auth);
            // Bounds a corrupt or hostile record; the writer already trims.
            trimOldest(epochAuthenticators, EPOCH_AUTH_RETENTION);
            return this;
        }
        public Builder putMembership(final int era, final long epoch, final String[] members) {
            Map<Long, String[]> byEpoch = membershipHistory.get(era);
            if (byEpoch == null) {
                byEpoch = new TreeMap<>();
                membershipHistory.put(era, byEpoch);
            }
            byEpoch.put(epoch, members == null ? new String[0] : members);
            trimOldest(byEpoch, MEMBERSHIP_EPOCH_RETENTION);
            while (membershipHistory.size() > MEMBERSHIP_ERA_RETENTION) {
                membershipHistory.remove(((TreeMap<Integer, ?>) membershipHistory).firstKey());
            }
            return this;
        }
        public Builder putMemberValidity(final int leafIndex, final MemberValidity v) {
            memberValidity.put(leafIndex, v); return this;
        }
        public Builder putParticipantKeyUpdate(final String msisdn, final long atMs) {
            lastParticipantKeyUpdate.put(msisdn == null ? "" : msisdn, atMs); return this;
        }

        /** Evicts first-seen, not LRU: a message nearing its cap keeps being touched. */
        public Builder putFtdResendCount(final String messageId, final int count) {
            final String k = messageId == null ? "" : messageId;
            if (!ftdResendCounts.containsKey(k)
                    && ftdResendCounts.size() >= MAX_FTD_RESEND_ENTRIES) {
                final java.util.Iterator<String> it = ftdResendCounts.keySet().iterator();
                if (it.hasNext()) { it.next(); it.remove(); }
            }
            ftdResendCounts.put(k, Math.max(0, count));
            return this;
        }
        public Builder sendsThisEpoch(final int v) { sendsThisEpoch = v; return this; }
        public Builder sendsSinceLeafRotation(final int v) { sendsSinceLeafRotation =
                v; return this; }
        public Builder selfLeftAtMs(final long v) { selfLeftAtMs = v; return this; }

        /** The decoded value; null and empty both mean none. */
        public Builder continuityToken(final byte[] v) {
            continuityToken = (v == null || v.length == 0) ? null : v.clone();
            return this;
        }

        public MlsConversationRecord build() { return new MlsConversationRecord(this); }
    }

    /** The record is rewritten whole, so its maps are bounded. */
    private static final int EPOCH_AUTH_RETENTION = 32;
    private static final int MEMBERSHIP_EPOCH_RETENTION = 32;
    private static final int MEMBERSHIP_ERA_RETENTION = 8;

    private static <K, V> void trimOldest(final Map<K, V> m, final int keep) {
        while (m.size() > keep && m instanceof TreeMap) {
            m.remove(((TreeMap<K, V>) m).firstKey());
        }
    }

    // Hand-rolled TLV, framed [version][varint payload length][payload]. The decoder skips unknown
    // fields; the exact length check catches truncation on a field boundary and trailing bytes.

    public byte[] encode() {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(512);
        putStr(out, F_IDENTITY, identity);
        putBytes(out, F_GROUP_ID, groupId);
        putStr(out, F_RCS_GROUP_ID, rcsGroupId);
        putStr(out, F_PEER_E164, peerE164);
        putVarint(out, F_HEALTH_STATUS, healthStatus);
        putVarint(out, F_SELF_HEAL_KIND, selfHealKind.wire);
        putVarint(out, F_SENDS_THIS_EPOCH, sendsThisEpoch);
        if (pendingOperation != null) {
            final ByteArrayOutputStream p = new ByteArrayOutputStream(64);
            putVarint(p, 1, pendingOperation.kind.wire);
            putVarint(p, 2, pendingOperation.attemptCount);
            putVarint(p, 3, pendingOperation.startedAtMs);
            if (pendingOperation.momentBinding != null) {
                putBytes(p, 4, momentBytes(pendingOperation.momentBinding));
            }
            putStr(p, 5, pendingOperation.context);
            putVarint(p, 6, pendingOperation.origin.wire);
            putBytes(out, F_PENDING_OP, p.toByteArray());
        }
        if (moment != null) putBytes(out, F_MOMENT, momentBytes(moment));
        if (lastHealthyMoment != null) {
            putBytes(out, F_LAST_HEALTHY_MOMENT, momentBytes(lastHealthyMoment));
        }
        if (recoveredAt != null) putBytes(out, F_RECOVERED_AT, momentBytes(recoveredAt));
        if (selfHealBudget.retryCount != 0 || selfHealBudget.firstAttemptAtMs != 0L
                || selfHealBudget.healedSinceClear) {
            final ByteArrayOutputStream p = new ByteArrayOutputStream(24);
            putVarint(p, 1, selfHealBudget.retryCount);
            putVarint(p, 2, selfHealBudget.firstAttemptAtMs);
            putVarint(p, 3, selfHealBudget.healedSinceClear ? 1 : 0);
            putBytes(out, F_SELF_HEAL_BUDGET, p.toByteArray());
        }
        if (storedStatusRequest != null) {
            final ByteArrayOutputStream p = new ByteArrayOutputStream(16);
            putVarint(p, 1, storedStatusRequest.requestedStatus);
            putVarint(p, 2, storedStatusRequest.cause);
            putBytes(out, F_STORED_STATUS_REQUEST, p.toByteArray());
        }
        if (expectedCommitKind != ExpectedCommitKind.NONE) {
            putVarint(out, F_EXPECTED_COMMIT_KIND, expectedCommitKind.wire);
        }
        for (final Map.Entry<Integer, Map<Long, String[]>> era : membershipHistory.entrySet()) {
            for (final Map.Entry<Long, String[]> ep : era.getValue().entrySet()) {
                final ByteArrayOutputStream p = new ByteArrayOutputStream(64);
                putVarint(p, 1, era.getKey());
                putVarint(p, 2, ep.getKey());
                for (final String m : ep.getValue()) putStr(p, 3, m);
                putBytes(out, F_MEMBERSHIP_HISTORY, p.toByteArray());
            }
        }
        for (final Map.Entry<Long, byte[]> e : epochAuthenticators.entrySet()) {
            final ByteArrayOutputStream p = new ByteArrayOutputStream(48);
            putVarint(p, 1, e.getKey());
            putBytes(p, 2, e.getValue());
            putBytes(out, F_EPOCH_AUTHENTICATORS, p.toByteArray());
        }
        for (final Map.Entry<Integer, MemberValidity> e : memberValidity.entrySet()) {
            final ByteArrayOutputStream p = new ByteArrayOutputStream(24);
            putVarint(p, 1, e.getKey());
            putVarint(p, 2, e.getValue().notBeforeSecs);
            putVarint(p, 3, e.getValue().notAfterSecs);
            putBytes(out, F_MEMBER_VALIDITY, p.toByteArray());
        }
        for (final Map.Entry<String, Long> e : lastParticipantKeyUpdate.entrySet()) {
            final ByteArrayOutputStream p = new ByteArrayOutputStream(32);
            putStr(p, 1, e.getKey());
            putVarint(p, 2, e.getValue());
            putBytes(out, F_LAST_PARTICIPANT_KEY_UPDATE, p.toByteArray());
        }
        for (final Map.Entry<String, Integer> e : ftdResendCounts.entrySet()) {
            final ByteArrayOutputStream p = new ByteArrayOutputStream(48);
            putStr(p, 1, e.getKey());
            putVarint(p, 2, e.getValue());
            putBytes(out, F_FTD_RESEND_COUNTS, p.toByteArray());
        }
        // New fields are appended here, after every existing one, so existing records keep their
        // byte layout.
        putVarint(out, F_SENDS_SINCE_LEAF_ROTATION, sendsSinceLeafRotation);
        putVarint(out, F_SELF_LEFT_AT, selfLeftAtMs);
        // Written only when present, so records without a token are unchanged.
        if (continuityToken.length > 0) putBytes(out, F_CONTINUITY_TOKEN, continuityToken);
        if (epochAuthEra != ERA_UNKNOWN) putVarint(out, F_EPOCH_AUTH_ERA, epochAuthEra);
        return frame(out.toByteArray());
    }

    /** {@code [VERSION][varint payload length][payload]}. */
    private static byte[] frame(final byte[] payload) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length + 8);
        out.write(VERSION);
        writeVarint(out, payload.length);
        out.write(payload, 0, payload.length);
        return out.toByteArray();
    }

    /**
     * {@code null} if the blob is not a record this build can read; never a partial record.
     */
    public static MlsConversationRecord decode(final byte[] raw) {
        if (raw == null || raw.length < 1) return null;
        final int version = raw[0] & 0xFF;
        if (version != VERSION && version != VERSION_UNFRAMED) return null;
        final Builder b = new Builder();
        try {
            int start = 1;
            if (version == VERSION) {
                final Cursor header = new Cursor(raw, 1);
                final long declaredLength = header.varint();
                start = header.position();
                if (declaredLength != (long) (raw.length - start)) return null;
            }
            final Cursor c = new Cursor(raw, start);
            while (c.hasMore()) {
                final int field = (int) c.varint();
                final int wireType = (int) c.varint();
                if (wireType == 0) {
                    final long v = c.varint();
                    switch (field) {
                        case F_HEALTH_STATUS: b.healthStatus = (int) v; break;
                        case F_SELF_HEAL_KIND: b.selfHealKind =
                                MlsSelfHealKind.fromWire((int) v); break;
                        case F_EXPECTED_COMMIT_KIND:
                            b.expectedCommitKind = ExpectedCommitKind.fromWire((int) v); break;
                        case F_SENDS_THIS_EPOCH: b.sendsThisEpoch = (int) v; break;
                        case F_SENDS_SINCE_LEAF_ROTATION:
                            b.sendsSinceLeafRotation = (int) v; break;
                        case F_SELF_LEFT_AT: b.selfLeftAtMs = v; break;
                        case F_EPOCH_AUTH_ERA: b.epochAuthEra = (int) v; break;
                        default: break;                        // unknown scalar: skip
                    }
                } else {
                    final byte[] v = c.bytes();
                    switch (field) {
                        case F_IDENTITY: b.identity = str(v); break;
                        case F_GROUP_ID: b.groupId = v; break;
                        case F_RCS_GROUP_ID: b.rcsGroupId = str(v); break;
                        case F_PEER_E164: b.peerE164 = str(v); break;
                        case F_MOMENT: b.moment = Moment.from(v); break;
                        case F_LAST_HEALTHY_MOMENT: b.lastHealthyMoment = Moment.from(v); break;
                        case F_RECOVERED_AT: b.recoveredAt = Moment.from(v); break;
                        case F_PENDING_OP: b.pendingOperation = decodePending(v); break;
                        case F_SELF_HEAL_BUDGET: b.selfHealBudget = decodeBudget(v); break;
                        case F_STORED_STATUS_REQUEST: b.storedStatusRequest =
                                decodeRequest(v); break;
                        case F_MEMBERSHIP_HISTORY: decodeMembership(b, v); break;
                        case F_EPOCH_AUTHENTICATORS: decodeEpochAuth(b, v); break;
                        case F_MEMBER_VALIDITY: decodeValidity(b, v); break;
                        case F_LAST_PARTICIPANT_KEY_UPDATE: decodeKeyUpdate(b, v); break;
                        case F_FTD_RESEND_COUNTS: decodeFtdResend(b, v); break;
                        case F_CONTINUITY_TOKEN: b.continuityToken = v; break;
                        default: break;                        // unknown length-delimited: skip
                    }
                }
            }
        } catch (final RuntimeException truncated) {
            // Never a partial record: a half-read one would read as Unknown with nothing pending.
            return null;
        }
        // Older records seed this from sendsThisEpoch; zero would delay rotation.
        if (b.sendsSinceLeafRotation == 0 && b.sendsThisEpoch > 0) {
            b.sendsSinceLeafRotation = b.sendsThisEpoch;
        }
        return b.build();
    }

    private static MlsPendingOperation decodePending(final byte[] v) {
        final Cursor c = new Cursor(v, 0);
        int kind = 0, attempts = 0, origin = 0;
        long started = 0L;
        Moment at = null;
        String ctx = "";
        while (c.hasMore()) {
            final int f = (int) c.varint();
            final int wt = (int) c.varint();
            if (wt == 0) {
                final long x = c.varint();
                if (f == 1) kind = (int) x;
                else if (f == 2) attempts = (int) x;
                else if (f == 3) started = x;
                else if (f == 6) origin = (int) x;
            } else {
                final byte[] x = c.bytes();
                if (f == 4) at = Moment.from(x);
                else if (f == 5) ctx = str(x);
            }
        }
        // Field 6 absent -> Origin.UNKNOWN, which does not escalate.
        return new MlsPendingOperation(MlsPendingOperation.Kind.fromWire(kind),
                MlsPendingOperation.Origin.fromWire(origin), attempts, started, at, ctx);
    }

    private static SelfHealBudget decodeBudget(final byte[] v) {
        final Cursor c = new Cursor(v, 0);
        int n = 0;
        long since = 0L;
        boolean healed = false;
        while (c.hasMore()) {
            final int f = (int) c.varint();
            final int wt = (int) c.varint();
            if (wt != 0) { c.bytes(); continue; }
            final long x = c.varint();
            if (f == 1) n = (int) x;
            else if (f == 2) since = x;
            else if (f == 3) healed = x != 0L;
        }
        return new SelfHealBudget(n, since, healed);
    }

    private static StatusRequest decodeRequest(final byte[] v) {
        final Cursor c = new Cursor(v, 0);
        int status = 0, cause = 0;
        while (c.hasMore()) {
            final int f = (int) c.varint();
            final int wt = (int) c.varint();
            if (wt != 0) { c.bytes(); continue; }
            final long x = c.varint();
            if (f == 1) status = (int) x;
            else if (f == 2) cause = (int) x;
        }
        return new StatusRequest(status, cause);
    }

    private static void decodeMembership(final Builder b, final byte[] v) {
        final Cursor c = new Cursor(v, 0);
        int era = 0;
        long epoch = 0L;
        final java.util.List<String> members = new java.util.ArrayList<>();
        while (c.hasMore()) {
            final int f = (int) c.varint();
            final int wt = (int) c.varint();
            if (wt == 0) {
                final long x = c.varint();
                if (f == 1) era = (int) x;
                else if (f == 2) epoch = x;
            } else {
                final byte[] x = c.bytes();
                if (f == 3) members.add(str(x));
            }
        }
        b.putMembership(era, epoch, members.toArray(new String[0]));
    }

    private static void decodeEpochAuth(final Builder b, final byte[] v) {
        final Cursor c = new Cursor(v, 0);
        long epoch = 0L;
        byte[] auth = new byte[0];
        while (c.hasMore()) {
            final int f = (int) c.varint();
            final int wt = (int) c.varint();
            if (wt == 0) { final long x = c.varint(); if (f == 1) epoch = x; }
            else { final byte[] x = c.bytes(); if (f == 2) auth = x; }
        }
        b.restoreEpochAuthenticator(epoch, auth);
    }

    private static void decodeValidity(final Builder b, final byte[] v) {
        final Cursor c = new Cursor(v, 0);
        int leaf = 0;
        long nb = 0L, na = 0L;
        while (c.hasMore()) {
            final int f = (int) c.varint();
            final int wt = (int) c.varint();
            if (wt != 0) { c.bytes(); continue; }
            final long x = c.varint();
            if (f == 1) leaf = (int) x;
            else if (f == 2) nb = x;
            else if (f == 3) na = x;
        }
        b.putMemberValidity(leaf, new MemberValidity(nb, na));
    }

    private static void decodeKeyUpdate(final Builder b, final byte[] v) {
        final Cursor c = new Cursor(v, 0);
        String who = "";
        long at = 0L;
        while (c.hasMore()) {
            final int f = (int) c.varint();
            final int wt = (int) c.varint();
            if (wt == 0) { final long x = c.varint(); if (f == 2) at = x; }
            else { final byte[] x = c.bytes(); if (f == 1) who = str(x); }
        }
        b.putParticipantKeyUpdate(who, at);
    }

    private static void decodeFtdResend(final Builder b, final byte[] v) {
        final Cursor c = new Cursor(v, 0);
        String mid = "";
        int n = 0;
        while (c.hasMore()) {
            final int f = (int) c.varint();
            final int wt = (int) c.varint();
            if (wt == 0) { final long x = c.varint(); if (f == 2) n = (int) x; }
            else { final byte[] x = c.bytes(); if (f == 1) mid = str(x); }
        }
        b.putFtdResendCount(mid, n);
    }

    private static void putVarint(final ByteArrayOutputStream o, final int field, final long v) {
        writeVarint(o, field);
        writeVarint(o, 0);
        writeVarint(o, v);
    }

    private static void putBytes(final ByteArrayOutputStream o, final int field, final byte[] v) {
        writeVarint(o, field);
        writeVarint(o, 2);
        writeVarint(o, v.length);
        o.write(v, 0, v.length);
    }

    private static void putStr(final ByteArrayOutputStream o, final int field, final String v) {
        if (v == null || v.isEmpty()) return;
        putBytes(o, field, v.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeVarint(final ByteArrayOutputStream o, long v) {
        while ((v & ~0x7FL) != 0) {
            o.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        o.write((int) v);
    }

    private static String str(final byte[] b) { return new String(b, StandardCharsets.UTF_8); }

    private static byte[] momentBytes(final Moment m) {
        final byte[] b = new byte[12];
        b[0] = (byte) (m.era >>> 24); b[1] = (byte) (m.era >>> 16);
        b[2] = (byte) (m.era >>> 8);  b[3] = (byte) m.era;
        for (int i = 0; i < 8; i++) b[4 + i] = (byte) (m.epoch >>> (56 - 8 * i));
        return b;
    }

    private static String hex(final byte[] b) {
        if (b == null) return "";
        final StringBuilder sb = new StringBuilder(b.length * 2);
        for (final byte x : b) sb.append(Character.forDigit((x >> 4) & 0xF, 16))
                                 .append(Character.forDigit(x & 0xF, 16));
        return sb.toString();
    }

    /** Throws on truncation, which {@link #decode} turns into a null record. */
    private static final class Cursor {
        private final byte[] b;
        private int p;

        Cursor(final byte[] b, final int p) { this.b = b; this.p = p; }

        boolean hasMore() { return p < b.length; }

        int position() { return p; }

        long varint() {
            long v = 0;
            int shift = 0;
            while (true) {
                if (p >= b.length) throw new IllegalStateException("truncated varint");
                final int x = b[p++] & 0xFF;
                v |= ((long) (x & 0x7F)) << shift;
                if ((x & 0x80) == 0) return v;
                shift += 7;
                if (shift > 63) throw new IllegalStateException("varint too long");
            }
        }

        byte[] bytes() {
            final int n = (int) varint();
            if (n < 0 || p + n > b.length) throw new IllegalStateException("truncated bytes");
            final byte[] out = new byte[n];
            System.arraycopy(b, p, out, 0, n);
            p += n;
            return out;
        }
    }

    /** The debug dump, used instead of an index. */
    public String dump() {
        final StringBuilder sb = new StringBuilder(256);
        sb.append("MlsConversationRecord{").append(MlsConversationKey.forLog(key()))
          .append(" rcsGid=").append(rcsGroupId)
          .append(" peer=").append(LogMask.number(peerE164))
          .append("\n  health=").append(MlsHealthStates.name(healthStatus))
          // Whether this health state parks inbound (RCC.16 §10.8).
          .append(MlsHealthPredicates.buffersInbound(healthStatus) ? " INBOUND-PARKED(G1)" : "")
          .append(" selfHealKind=").append(selfHealKind)
          .append(" moment=").append(moment)
          .append(" lastHealthy=").append(lastHealthyMoment)
          .append(" recoveredAt=").append(recoveredAt)
          .append("\n  pending=").append(pendingOperation)
          .append(" ").append(selfHealBudget)
          .append("\n  statusRequest=").append(storedStatusRequest)
          .append(" expectedCommit=").append(expectedCommitKind)
          .append(" sendsThisEpoch=").append(sendsThisEpoch)
          .append(" sendsSinceLeafRotation=").append(sendsSinceLeafRotation)
          .append(selfLeft() ? " WE-LEFT@" + selfLeftAtMs : "")
          .append("\n  epochAuths=").append(epochAuthenticators.size())
          .append(epochAuthEra == ERA_UNKNOWN ? "@era?" : "@era" + epochAuthEra)
          .append(" membershipEras=").append(membershipHistory.size())
          .append(" memberValidity=").append(memberValidity.size())
          .append(" keyUpdates=").append(lastParticipantKeyUpdate.size())
          .append(" ftdResends=").append(ftdResendCounts.size())
          // Length only: the token is a group secret.
          .append(" continuityToken=").append(continuityToken.length).append("B")
          .append("}");
        return sb.toString();
    }

    @Override public String toString() {
        return "MlsConversationRecord{" + key() + " " + MlsHealthStates.name(healthStatus)
                + (selfLeft() ? " WE-LEFT" : "") + " " + moment + "}";
    }
}
