/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

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
}
