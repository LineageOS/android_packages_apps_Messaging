/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Op;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ServerPack;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Look;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import com.android.messaging.rcs.log.LogMask;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Recognises a relayed {@code ServerMlsRcsMessage} bundle: which bare {@code MLSMessage}s it holds,
 * in apply order, and whether the whole blob must be forwarded. Also the server-pack and roster
 * helpers built on it. The content variant is read from RFC 9420 wire formats and is independent of
 * the outer arm (see {@link MlsServerMessage}). Proposals are reported as {@link Variant#COMMIT}:
 * separating them would mean decoding {@code FramedContent.content_type}.
 */
public final class MlsServerBundle {

    private MlsServerBundle() {}

    /** Descent depth. A subject-change bundle nests at {@code f2.f1.f2.{f1,f3}}. */
    private static final int MAX_DEPTH = 6;
    /** Node budget, so a hostile or malformed payload cannot make the walk expensive. */
    private static final int MAX_CONTAINERS = 512;
    /** Field numbers descended into when looking for sub-containers. */
    private static final int MAX_CONTAINER_FIELD = 16;
    /** Field numbers read when looking for the artifacts themselves. */
    private static final int MAX_ARTIFACT_FIELD = 5;

    /**
     * The log token {@link MlsInvariantScan} looks for when an arm whose type is assigned by
     * elimination shows up. Shared so the scan and the emitter cannot drift.
     */
    public static final String ARM_UNPROVEN_MARKER = "6.8 ARM UNPROVEN OBSERVED";

    /** What the bundle contains, from the RFC 9420 wire formats inside it. */
    public enum Variant {
        /**
         * A {@code Welcome}. Its {@code ratchet_tree} travels alongside, so the whole blob must
         * reach the joiner: see {@link Recognised#forwardWholeBlob}.
         */
        WELCOME,
        /** A {@code GroupInfo}: the re-drive input. */
        GROUP_INFO,
        /** A {@code PublicMessage}: a Commit, or a proposal (not separated). */
        COMMIT,
        /** Only {@code PrivateMessage}s: application traffic, or an in-band key delivery. */
        APPLICATION,
        /** The server acknowledging one of our sends. Arm 4 carries no MLSMessage. */
        ACCEPTED,
        /** Nothing in the blob parses as an {@code MLSMessage}. */
        UNRECOGNISED,
    }

    /** The typed result the host reads instead of inspecting bytes. */
    public static final class Recognised {

        /**
         * The outer {@code ServerMlsRcsMessage} oneof arm, or {@link MlsServerMessage.Arm#NONE}.
         */
        public final MlsServerMessage.Arm arm;
        /** The arm's payload, exactly as it appeared. Never null. */
        public final byte[] armPayload;
        /** What the bundle contains, from its wire formats. Independent of {@link #arm}. */
        public final Variant variant;

        /**
         * Every bare {@code MLSMessage} found, in the bundle's apply order, deduplicated. Order
         * matters: a metadata commit is the commit at {@code f1} then the key delivery at
         * {@code f3}, encrypted at the post-commit epoch.
         */
        public final List<byte[]> messages;

        /** Exactly what to hand the MLS owner, in order: {@link #messages}, or the whole blob. */
        public final List<byte[]> delivery;

        /**
         * A {@code Welcome} is present, so the whole blob is forwarded: our Welcomes are tree-less
         * and the joiner splices in the {@code ratchet_tree} from a separate field. Also true when
         * nothing parsed, so an unrecognised blob is passed on rather than swallowed.
         */
        public final boolean forwardWholeBlob;

        /** True when the arm-1 payload was taken verbatim and the descent skipped entirely. */
        public final boolean takenVerbatim;

        /** {@code AcceptedMlsRcsMessage.message_id} on arm 4; {@code null} otherwise. */
        public final String acceptedMessageId;

        /**
         * The 32-byte {@code f4} on arms 2 and 3, or {@code null}: the {@code MlsGroupInfo} epoch
         * authenticator. Evidence, not proof, of the arm's type, since both candidate types share
         * the schema; read it with {@link #variant}.
         */
        public final byte[] groupInfoEpochAuthenticator;

        Recognised(final MlsServerMessage.Arm arm, final byte[] armPayload, final Variant variant,
                final List<byte[]> messages, final List<byte[]> delivery,
                final boolean forwardWholeBlob, final boolean takenVerbatim,
                final String acceptedMessageId, final byte[] groupInfoEpochAuthenticator) {
            this.arm = arm;
            this.armPayload = armPayload == null ? new byte[0] : armPayload;
            this.variant = variant;
            this.messages = Collections.unmodifiableList(messages);
            this.delivery = Collections.unmodifiableList(delivery);
            this.forwardWholeBlob = forwardWholeBlob;
            this.takenVerbatim = takenVerbatim;
            this.acceptedMessageId = acceptedMessageId;
            this.groupInfoEpochAuthenticator = groupInfoEpochAuthenticator;
        }

        /**
         * Arm 4: a server acknowledgement of one of our sends. The host must treat it as handled;
         * fed to an engine it is malformed, and a failure report would answer the server's success.
         */
        public boolean isAccepted() { return arm == MlsServerMessage.Arm.ACCEPTED; }

        /** One log line: the arm and the content together. */
        public String logLine() {
            final StringBuilder sb = new StringBuilder("6.8 bundle: arm=").append(arm)
                    .append('(').append(arm.field).append(") variant=").append(variant)
                    .append(" armPayload=").append(armPayload.length).append('B')
                    .append(" messages=").append(messages.size());
            for (final byte[] m : messages) {
                sb.append(' ').append(MlsWireScan.wireFormatName(MlsWireScan.wireFormatOf(m)))
                  .append('/').append(m.length).append('B');
            }
            if (takenVerbatim) sb.append(" VERBATIM(arm-1 payload; descent skipped)");
            if (forwardWholeBlob) {
                sb.append(messages.isEmpty()
                        ? " forwardWholeBlob(nothing parsed)"
                        : " forwardWholeBlob(Welcome — the ratchet_tree rides alongside it)");
            }
            if (acceptedMessageId != null) sb.append(" acceptedMessageId=").append(
                    MlsMessageId.forLog(acceptedMessageId));
            if (groupInfoEpochAuthenticator != null) {
                sb.append(" f4=").append(groupInfoEpochAuthenticator.length)
                  .append("B (GroupInfo-shaped)");
            }
            return sb.toString();
        }

        /**
         * The warning line for an arm assigned by elimination, or {@code null}. Carries
         * {@link #ARM_UNPROVEN_MARKER} and the content variant: {@link Variant#WELCOME} or
         * {@link Variant#COMMIT} on arm 2 corroborates the elimination;
         * {@link Variant#GROUP_INFO}, or a 32-byte {@code f4} with no commit, contradicts it.
         */
        public String armEvidenceLine() {
            if (arm.assignmentIsProven()) return null;
            return ARM_UNPROVEN_MARKER + " — " + logLine()
                    + ". Arm 3 (MlsGroupInfo) is settled via the epoch-authenticator "
                    + "agreement, so this arm is ServerCommitBundle BY ELIMINATION. The variant "
                    + "above is read from the RFC 9420 wire formats INSIDE the bundle and is "
                    + "independent of the arm number, so the two can be compared: WELCOME or "
                    + "COMMIT corroborates the elimination; GROUP_INFO (or a 32B f4 with no commit) "
                    + "contradicts it, and MlsServerMessage.Arm.assignmentIsProven is the ONE place "
                    + "to flip. Record it either way.";
        }

        @Override public String toString() { return logLine(); }
    }

    /**
     * Recognise a relayed control blob: arm 4 returns before any descent; arm 1 carries the
     * MLSMessage directly and is taken verbatim; otherwise descend structurally over exact protobuf
     * lengths, then classify.
     *
     * @param wire the relayed control payload with the backend envelope already stripped
     * @return never null
     */
    public static Recognised recognise(final byte[] wire) {
        final byte[] blob = wire == null ? new byte[0] : wire;
        final MlsServerMessage.Parsed armed = MlsServerMessage.parse(blob);

        if (armed.isAccepted()) {
            return new Recognised(armed.arm, armed.payload, Variant.ACCEPTED,
                    new ArrayList<byte[]>(), new ArrayList<byte[]>(),
                    /*forwardWholeBlob=*/ false, /*takenVerbatim=*/ false,
                    MlsServerMessage.acceptedMessageId(armed.payload),
                    /*groupInfoEpochAuthenticator=*/ null);
        }

        final byte[] f4 = groupInfoShapedF4(armed);
        final List<byte[]> verbatim = takeArmOneVerbatim(armed);
        final List<byte[]> messages = verbatim != null ? verbatim : dedup(descend(blob));

        // Classified over the same list the delivery is built from, so the two cannot disagree.
        final List<byte[]> classifyOver =
                messages.isEmpty() ? Collections.singletonList(blob) : messages;
        final boolean carriesWelcome = contains(classifyOver, MlsWireScan.WF_WELCOME);
        final boolean whole = carriesWelcome || messages.isEmpty();

        return new Recognised(armed.arm, armed.payload, classify(classifyOver), messages,
                whole ? Collections.singletonList(blob) : messages,
                whole, /*takenVerbatim=*/ verbatim != null, /*acceptedMessageId=*/ null, f4);
    }

    /**
     * Arm 1's payload as-is (bare or {@code mls_varint}-wrapped), or {@code null} to fall through
     * to the descent rather than drop a payload we could not parse.
     */
    private static List<byte[]> takeArmOneVerbatim(final MlsServerMessage.Parsed armed) {
        if (armed.arm != MlsServerMessage.Arm.RAW || armed.payload.length == 0) return null;
        if (MlsWireScan.isMlsMessage(armed.payload)) {
            return new ArrayList<byte[]>(Collections.singletonList(armed.payload));
        }
        final byte[] stripped = MlsWireScan.stripMlsVarint(armed.payload);
        if (stripped != null && MlsWireScan.isMlsMessage(stripped)) {
            return new ArrayList<byte[]>(Collections.singletonList(stripped));
        }
        return null;
    }

    /** The bundle's {@code f4} on arms 2/3 when it is exactly 32 bytes, else {@code null}. */
    private static byte[] groupInfoShapedF4(final MlsServerMessage.Parsed armed) {
        if (armed.arm != MlsServerMessage.Arm.SERVER_COMMIT_BUNDLE
                && armed.arm != MlsServerMessage.Arm.MLS_GROUP_INFO) {
            return null;
        }
        final byte[] v = RccProto.field(armed.payload, 4);
        return (v != null && v.length == 32) ? v : null;
    }

    /**
     * Every bare {@code MLSMessage} reachable in the blob, in wire order: breadth-first over
     * length-delimited fields, bounded in depth and nodes. Each candidate is tried as-is and
     * {@code mls_varint}-stripped, since a bundle field may hold either form.
     */
    private static List<byte[]> descend(final byte[] wire) {
        final List<byte[]> out = new ArrayList<byte[]>();
        if (wire.length == 0) return out;
        final List<byte[]> containers = new ArrayList<byte[]>();
        List<byte[]> level = new ArrayList<byte[]>();
        level.add(wire);
        containers.add(wire);
        for (int depth = 0; depth < MAX_DEPTH && !level.isEmpty()
                && containers.size() < MAX_CONTAINERS; depth++) {
            final List<byte[]> next = new ArrayList<byte[]>();
            for (final byte[] node : level) {
                for (int f = 1; f <= MAX_CONTAINER_FIELD; f++) {
                    for (final byte[] child : RccProto.repeatedField(node, f)) {
                        if (child.length < 4 || containers.size() >= MAX_CONTAINERS) continue;
                        containers.add(child);
                        next.add(child);
                    }
                }
            }
            level = next;
        }
        for (final byte[] c : containers) {
            for (int f = 1; f <= MAX_ARTIFACT_FIELD; f++) {
                for (final byte[] v : RccProto.repeatedField(c, f)) {
                    if (MlsWireScan.isMlsMessage(v)) out.add(v);
                    final byte[] s = MlsWireScan.stripMlsVarint(v);
                    if (s != null && MlsWireScan.isMlsMessage(s)) out.add(s);
                }
            }
        }
        return out;
    }

    /**
     * First occurrence wins, preserving the bundle's order. The descent finds the same message from
     * several containers, and applying a commit twice fails.
     */
    private static List<byte[]> dedup(final List<byte[]> in) {
        final List<byte[]> out = new ArrayList<byte[]>();
        final Map<Integer, List<byte[]>> seen = new HashMap<Integer, List<byte[]>>();
        for (final byte[] c : in) {
            if (c == null || c.length == 0) continue;
            final Integer key = Integer.valueOf(Arrays.hashCode(c));
            List<byte[]> bucket = seen.get(key);
            if (bucket == null) {
                bucket = new ArrayList<byte[]>();
                seen.put(key, bucket);
            }
            boolean dup = false;
            for (final byte[] prior : bucket) {
                if (Arrays.equals(prior, c)) { dup = true; break; }
            }
            if (!dup) {
                bucket.add(c);
                out.add(c);
            }
        }
        return out;
    }

    private static boolean contains(final List<byte[]> msgs, final int wireFormat) {
        for (final byte[] m : msgs) {
            if (MlsWireScan.wireFormatOf(m) == wireFormat) return true;
        }
        return false;
    }

    /**
     * Content-derived variant, by consequence: a Welcome changes delivery, a GroupInfo selects the
     * re-drive path, a Commit changes the epoch. {@link Variant#APPLICATION} means only private
     * messages.
     */
    private static Variant classify(final List<byte[]> msgs) {
        if (contains(msgs, MlsWireScan.WF_WELCOME)) return Variant.WELCOME;
        if (contains(msgs, MlsWireScan.WF_GROUP_INFO)) return Variant.GROUP_INFO;
        if (contains(msgs, MlsWireScan.WF_PUBLIC_MESSAGE)) return Variant.COMMIT;
        if (contains(msgs, MlsWireScan.WF_PRIVATE_MESSAGE)) return Variant.APPLICATION;
        return Variant.UNRECOGNISED;
    }

    public static java.util.List<String> rosterFromPack(final MlsShellPort shell,
            final byte[] packed) {
        if (packed == null) return null;
        final java.util.List<String> roster = new java.util.ArrayList<>();
        for (int i = 2; ; i++) {
            final byte[] m = MlsWireScan.firstPacked(packed, i);
            if (m == null) break;
            final String e164 = new String(m, java.nio.charset.StandardCharsets.UTF_8);
            if (!e164.isEmpty() && !e164.equals(shell.selfE164()) && !roster.contains(e164)) {
                roster.add(e164);
            }
        }
        return roster;
    }

    /**
     * Whether the server's roster still lists us; {@link #rosterFromPack} filters self out and
     * cannot answer this. If we are not listed, self-heal cannot help and the remedy is a Welcome
     * from a member.
     *
     * @return TRUE or FALSE, or null when the pack carries no usable roster (unknown is not false)
     */
    public static Boolean selfInServerRoster(final MlsShellPort shell, final MlsLogSink log,
            final byte[] packed) {
        if (packed == null) return null;
        final String self = shell.selfE164();
        if (self == null || self.isEmpty()) return null;
        boolean sawAny = false;
        boolean found = false;
        final StringBuilder raw = new StringBuilder();
        for (int i = 2; ; i++) {
            final byte[] m = MlsWireScan.firstPacked(packed, i);
            if (m == null) break;
            final String e164 = new String(m, java.nio.charset.StandardCharsets.UTF_8);
            if (e164.isEmpty()) continue;
            sawAny = true;
            raw.append(' ').append(LogMask.number(e164));
            if (e164.equals(self)) found = true;
        }
        // Logged unfiltered: whether the server's roster includes the requester decides whether
        // this predicate means anything, and the filtered list cannot show it.
        log.i("MlsServerBundle: SERVER ROSTER (unfiltered) self=" + LogMask.number(self)
                + " present=" + found + " members[" + (sawAny ? raw.toString().trim() : "<none>")
                + "]");
        if (!sawAny) return null;
        return found ? Boolean.TRUE : Boolean.FALSE;
    }

    /**
     * The server's pack for a rebuild, fetched once while the local group is still intact: slot 0
     * is the GroupInfo the re-establish carries, slots 2+ the roster. Every no-pack arm degrades
     * the rebuild the same way; {@link MlsServerPackOutcome} records which one applied.
     */
    public static ServerPack serverPackForRebuild(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164, final String key) {
        if (rcsGroupId == null || rcsGroupId.isEmpty()) {
            return ServerPack.none(MlsServerPackOutcome.NOT_A_GROUP);
        }
        final Group g = shell.getGroup(key);
        // Returns before the fetch ledger sees the attempt: the look is anchored on local state.
        if (g == null || g.groupId == null) {
            return ServerPack.none(MlsServerPackOutcome.NO_LOCAL_STATE_TO_ASK_WITH);
        }
        try {
            // Charged to REBUILD: the rebuild is the decision that spends.
            final Look<byte[]> look =
                    shell.fetchServerPack(MlsFetchLedger.Caller.REBUILD, rcsGroupId, peerE164, g);
            if (look.refused()) {
                log.w("MlsServerBundle: the rebuild of " + MlsConversationKey.forLog(key)
                        + " has NO server pack "
                        + "— the fetch ledger refused the look, so the roster falls back to the "
                        + "recorded membership and the re-establish carries nothing. Nothing was "
                        + "asked of the server; this is not a server that would not answer.");
                return ServerPack.none(MlsServerPackOutcome.REFUSED_BY_LEDGER);
            }
            final byte[] answer = look.orNull();
            if (answer == null) {
                return ServerPack.none(MlsServerPackOutcome.SERVER_HAD_NOTHING);
            }
            return ServerPack.of(answer);
        } catch (final Throwable t) {
            log.w("MlsServerBundle: could not fetch the server pack for a rebuild of "
                    + MlsConversationKey.forLog(key)
                    + " — the roster falls back to the recorded membership and the "
                    + "re-establish carries nothing", t);
            return ServerPack.none(MlsServerPackOutcome.LOOK_FAILED);
        }
    }

    /**
     * The roster checked against RCC.16's 30-day remaining-lifetime floor (RCC.16 A.4.3.1), which
     * refuses membership changes a month before any credential has expired. A second reading of the
     * data, never an actor: raising the expiry threshold instead would loop futile era advances,
     * since RCC.16 A.4.2.2 stops the KDS serving the stale member's KeyPackage. See
     * docs/mls/credentials.md.
     *
     * @return the report, or null if the roster cannot be read (not an empty report)
     */
    public static MlsCredentialFloor.Report rosterFloorReport(final MlsConfig cfg,
            final MlsSession session, final MlsLogSink log, final Group g) {
        if (session == null || g == null || g.groupId == null) return null;
        final MlsSession sess = session;
        final java.util.Map<Integer, long[]> v = sess.memberValidity(g.groupId);
        if (v == null || v.isEmpty()) return null;
        // Names are best-effort; their absence must not stop the count.
        java.util.Map<Integer, String> names = java.util.Collections.emptyMap();
        try {
            final java.util.Map<Integer, MlsParticipantKeyResync.Leaf> leaves =
                    sess.memberParticipantKeys(g.groupId);
            if (leaves != null && !leaves.isEmpty()) {
                final java.util.Map<Integer, String> byIndex = new java.util.LinkedHashMap<>();
                for (final java.util.Map.Entry<Integer, MlsParticipantKeyResync.Leaf> e
                        : leaves.entrySet()) {
                    if (e.getValue() != null) byIndex.put(e.getKey(), e.getValue().participant);
                }
                names = byIndex;
            }
        } catch (final Throwable t) {
            log.i("MlsServerBundle: could not name the roster's leaves for the "
                    + "credential-floor report — reporting by leaf index instead");
        }
        return MlsCredentialFloor.classify(v, names, System.currentTimeMillis() / 1000L,
                cfg.kpMinRemainingDays);
    }

    /**
     * After a peer's commit lands, log members whose certificate window is now invalid. The engine
     * validates credentials on the add path; this reports drift since admission. A log only: the
     * maintenance policy's expired-member count is the single actor. Unreadable leaves are counted
     * apart from invalid ones. Never throws.
     */
    public static void auditRosterAfterPeerCommit(final MlsSession session, final MlsLogSink log,
            final Group g, final String messageId, final String fromE164) {
        try {
            if (session == null || g == null || g.groupId == null) return;
            final java.util.Map<Integer, long[]> v =
                    session.memberValidity(g.groupId);
            if (v == null || v.isEmpty()) return;
            final long now = System.currentTimeMillis() / 1000L;
            int expired = 0;
            int unreadable = 0;
            final StringBuilder bad = new StringBuilder();
            for (final java.util.Map.Entry<Integer, long[]> e : v.entrySet()) {
                final long nb = e.getValue()[0];
                final long na = e.getValue()[1];
                if (nb == 0 && na == 0) {
                    unreadable++;
                    bad.append(" leaf=").append(e.getKey()).append("(UNREADABLE)");
                } else if (na <= now) {
                    expired++;
                    bad.append(" leaf=").append(e.getKey())
                       .append("(EXPIRED ").append((now - na) / 86400L).append("d ago)");
                } else if (nb > now) {
                    expired++;
                    bad.append(" leaf=").append(e.getKey())
                       .append("(NOT YET VALID for ").append((nb - now) / 86400L).append("d)");
                }
            }
            if (expired == 0 && unreadable == 0) return;
            // Warn, not error: nothing was stopped.
            log.w("MlsServerBundle: after a commit from " + LogMask.number(fromE164) + " ("
                    + MlsMessageId.forLog(messageId)
                    + ") the roster of " + MlsTrace.groupId(g.groupId) + " holds " + expired
                    + " member(s) with an INVALID certificate window and " + unreadable
                    + " we could not read, out of " + v.size() + ":" + bad + ". These were VALID "
                    + "WHEN ADMITTED — the engine validates a member's credential on the add path, "
                    + "in both directions — so this is credential DRIFT since they joined, not an "
                    + "unchecked add. Nothing is blocked here: expiredMemberCount feeds "
                    + "MlsMaintenancePolicy, which is what acts on it.");
        } catch (final Throwable t) {
            log.w("MlsServerBundle: roster audit after a peer commit failed", t);
        }
    }

    /**
     * Refuse an add or remove locally when a member's credential is inside the floor, so the
     * failure names the member instead of arriving as a bare {@code PERMISSION_DENIED} from a
     * server that validates at device time plus 30 days. Self-Update and leave are never gated:
     * Self-Update is the repair (RCC.16 A.4.3.2). {@link MlsConfig#KEY_FLOOR_PRECHECK} turns it
     * off.
     *
     * @return true to proceed
     */
    public static boolean membershipChangeAllowedByFloor(final MlsConfig cfg,
            final MlsSession session, final MlsLogSink log, final String key, final Group g,
            final Op op, final String what) {
        if (op != Op.ADD && op != Op.REMOVE) return true;
        if (!cfg.floorPrecheck) return true;
        final MlsCredentialFloor.Report r = MlsServerBundle.rosterFloorReport(cfg, session, log, g);
        // An unreadable roster proceeds: this gate makes a failure legible, it does not invent one.
        if (r == null || !r.membershipChangesWouldBeRefused()) return true;
        log.e("MlsServerBundle: REFUSING " + what + " on " + MlsConversationKey.forLog(key)
                + " locally — "
                + r.insideFloor + " member(s) inside RCC.16's " + cfg.kpMinRemainingDays
                + "-day remaining-lifetime floor and " + r.expired + " expired: " + r.below
                + ". The server validates member credentials at now+" + cfg.kpMinRemainingDays
                + "d (A.4.3.1 §1(a); Invariant 17 evaluates the WHOLE roster), so this Commit would "
                + "come back PERMISSION_DENIED \"Time-related validation error\" naming one of those "
                + "numbers. THIS IS NOT SOMETHING WE CAN FIX FOR THEM: a Remove gets no expiry "
                + "carve-out (A.4.3.2 exempts only the committer's own leaf) and A.4.2.2 stops the "
                + "KDS handing out their KeyPackage, so only that member's own Self-Update clears "
                + "it. Set " + MlsConfig.KEY_FLOOR_PRECHECK + "=0 to send it anyway.");
        return false;
    }

    /**
     * Whether the peer that sent a negative-delivery report is in the conversation it names,
     * checked against the server's roster since ours may be the stale one. Fails open on a fetch
     * failure or a ledger refusal: dropping a real report leaves a peer diverged, acting on one
     * costs a redundant repair.
     */
    public static boolean reporterIsAMember(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164, final String key) {
        // A 1:1 has no roster to check: the peer is the conversation.
        if (rcsGroupId == null || rcsGroupId.isEmpty()) return true;
        // Runs on the inbound path, possibly before any session exists; fail open like a fetch
        // failure.
        if (!shell.ensureSession()) return true;
        final Group g = shell.getGroup(key);
        if (g == null || g.groupId == null) return true;
        // Charges the fetch ledger, because the rate is driven by a failing peer's reports rather
        // than by a loop of ours, yet still fails open on a refusal: our rate ledger is not
        // evidence about the peer. The log says which of refusal or unreadable answer it was.
        final Look<byte[]> pack = shell.fetchServerPack(MlsFetchLedger.Caller.PEER_REPORT_VERIFY,
                rcsGroupId, peerE164, g);
        if (pack.refused()) {
            log.w("MlsServerBundle: " + MlsFetchLedger.describeFailOpen(
                    MlsFetchLedger.Caller.PEER_REPORT_VERIFY, key,
                    "treating " + LogMask.number(peerE164) + " as a member of " + key
                    + " without checking the "
                    + "server roster"));
            return true;
        }
        final java.util.List<String> roster = MlsServerBundle.rosterFromPack(shell, pack.orNull());
        if (roster == null) {
            log.w("MlsServerBundle: could not fetch the roster to verify "
                    + LogMask.number(peerE164)
                    + " belongs to " + MlsConversationKey.forLog(key)
                    + " — proceeding anyway; dropping a real report would "
                    + "leave that peer diverged forever, which is worse than a redundant repair. "
                    + "(We DID ask: this is an unreadable answer, not a refused look.)");
            return true;
        }
        if (roster.contains(peerE164)) return true;
        log.w("MlsServerBundle: " + LogMask.number(peerE164) + " is NOT in the server roster for "
                + MlsConversationKey.forLog(key) + " " + LogMask.numbers(roster));
        return false;
    }

    /**
     * The members we believe this group has, excluding ourselves, from the record's membership
     * history at the newest epoch of the current era.
     *
     * @return the members, or null if nothing usable was recorded for this era (not an empty group)
     */
    public static java.util.List<String> ourRoster(final MlsShellPort shell, final MlsLogSink log,
            final String key, final Group g) {
        final MlsConversationRecord rec = MlsRecordState.recordFor(shell, log, key);
        if (rec == null || g == null || g.groupId == null) return null;
        final int era = MlsAppMessage.eraFrom(shell.session().eraEpoch(g.groupId));
        final java.util.Map<Long, String[]> byEpoch = rec.membershipHistory.get(era);
        if (byEpoch == null || byEpoch.isEmpty()) return null;
        long newest = Long.MIN_VALUE;
        String[] members = null;
        for (final java.util.Map.Entry<Long, String[]> e : byEpoch.entrySet()) {
            if (e.getKey() >= newest) { newest = e.getKey(); members = e.getValue(); }
        }
        if (members == null) return null;
        // A zero-length stored set is the self-departure mark, not a roster: every other writer
        // includes our own MSISDN. Null makes callers defer to the maintenance pass, which records
        // the server's roster once the epoch authenticator confirms the group; an empty list would
        // read every server member as new and trigger an era advance after a rejoin.
        if (members.length == 0) return null;
        final java.util.List<String> out = new java.util.ArrayList<>();
        final String self = shell.selfE164();
        for (final String m : members) {
            if (m != null && !m.isEmpty() && !m.equals(self)) out.add(m);
        }
        return out;
    }

    /**
     * The group's roster as certified MSISDNs (each leaf's {@code tel:} SAN), or {@code null} if
     * any leaf is unreadable. The caller diffs two of these to find departures, so a short roster
     * would report an unreadable member as departed.
     */
    public static java.util.List<String> mlsRosterMsisdns(final MlsShellPort shell,
            final MlsLogSink log, final byte[] groupId) {
        final MlsSession eng = shell.openMlsSession();
        if (eng == null || groupId == null) return null;
        final java.util.Map<Integer, MlsParticipantKeyResync.Leaf> leaves =
                eng.memberParticipantKeys(groupId);
        if (leaves == null || leaves.isEmpty()) return null;
        final java.util.List<String> out = new java.util.ArrayList<>(leaves.size());
        for (final MlsParticipantKeyResync.Leaf leaf
                : leaves.values()) {
            if (leaf == null || leaf.participant == null || leaf.participant.isEmpty()) {
                log.w("MlsServerBundle: leaf "
                        + (leaf == null ? "?" : Integer.toString(leaf.index)) + " of "
                        + MlsTrace.groupId(groupId) + " carries no readable MSISDN — declining to "
                        + "report a roster at all rather than a short one, since the caller "
                        + "subtracts two of these and a missing member reads as a departed one");
                return null;
            }
            out.add(leaf.participant);
        }
        return out;
    }

    public static java.util.List<String> rosterForRebuild(final MlsShellPort shell,
            final MlsLogSink log, final String rcsGroupId, final String peerE164,
            final String key, final ServerPack pack) {
        final byte[] serverPack = pack.bytes();
        if (rcsGroupId == null || rcsGroupId.isEmpty()) {
            // 1:1: the peer is the roster.
            return peerE164 == null
                    ? java.util.Collections.<String>emptyList()
                    : java.util.Collections.singletonList(peerE164);
        }
        final java.util.List<String> out = new java.util.ArrayList<>();
        final String self = shell.selfE164();
        if (serverPack != null) {
            final java.util.List<String> server = MlsServerBundle.rosterFromPack(shell, serverPack);
            if (server != null && !server.isEmpty()) {
                for (final String m : server) {
                    if (m != null && !m.isEmpty() && !m.equals(self)) out.add(m);
                }
                log.i("MlsServerBundle: rebuild roster for " + MlsConversationKey.forLog(key)
                        + " from the SERVER pack: " + LogMask.numbers(out));
                return out;
            }
        }
        final java.util.List<String> recorded =
                MlsRecordState.recordedRoster(shell, log, key, self);
        if (recorded != null && !recorded.isEmpty()) {
            // The outcome names why the server pack was missing.
            log.w("MlsServerBundle: rebuild roster for " + MlsConversationKey.forLog(key)
                    + " could not be read "
                    + "from the server — " + pack.outcome().line() + " — falling back to the LAST "
                    + "RECORDED membership " + LogMask.numbers(recorded)
                    + ". If the roster has changed since, the "
                    + "create is refused with mlsError 5 rather than silently building the wrong "
                    + "group.");
            return recorded;
        }
        // Last resort: the RCS conversation's participants, which survive a forget of all MLS state
        // and are the set the server matches the create against.
        out.addAll(shell.bugleRoster(rcsGroupId, self));
        if (!out.isEmpty()) {
            log.w("MlsServerBundle: rebuild roster for " + MlsConversationKey.forLog(key)
                    + " taken from the RCS "
                    + "CONVERSATION participants " + LogMask.numbers(out)
                    + " — no MLS-side source survived (no local "
                    + "group, no recorded membership). This is the state a previous forget leaves, "
                    + "and the RCS roster is the one input it cannot damage.");
            return out;
        }
        return null;
    }

    /**
     * The server's view of a conversation: GroupInfo at slot 0, ratchet tree at 1, roster from 2.
     * No ledger of its own; each caller declares which budget it spends.
     */
    public static Look<byte[]> fetchServerPack(final MlsShellPort shell,
            final MlsFetchLedger.Caller caller,
            final String rcsGroupId, final String peerE164, final Group g) {
        return shell.lookMissedCommits(caller,
                MlsConversationKey.canonicalKey(rcsGroupId, peerE164), peerE164, rcsGroupId,
                MlsAppMessage.eraFrom(shell.session().eraEpoch(g.groupId)),
                shell.session().epochAuth(g.groupId));
    }
}
