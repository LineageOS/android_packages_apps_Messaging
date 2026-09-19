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
package com.android.messaging.rcs.engine.mls;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The engine's entry point for a relayed {@code ServerMlsRcsMessage} bundle — rework item
 * {@code 6.8}, §10.6.
 *
 * <h2>What was missing, precisely</h2>
 *
 * <p>{@link MlsServerMessage} already gives the host the outer arm §10.6 sanctions it to select.
 * But it answers <b>which arm</b> and stops there, and "which arm" is not what the inbound path
 * needs — it needs <b>what is in the bundle</b>: which bare {@code MLSMessage}s, in what order, and
 * whether the blob has to be forwarded WHOLE because it carries a Welcome whose {@code ratchet_tree}
 * rides alongside it. None of that had an engine entry point, so the host worked it out by
 * BYTE-SCANNING: a recursive protobuf descent collecting anything shaped like {@code 00 01 00 0X},
 * plus a hand-written {@code c[0]==0 && c[1]==1 && c[2]==0 && c[3]==3} for the Welcome case. §10.6's
 * boundary — hand over the bytes, read the typed result — existed for the arm and nowhere below it.
 *
 * <p>The Welcome special-case is the tell: it exists <i>because the scan
 * cannot tell what it is looking at</i>. A scan that recognised a Welcome would not need a rule
 * saying "if you happen to see these four bytes, throw your work away and forward everything".
 *
 * <h2>What this class does NOT claim</h2>
 *
 * <p>{@link Variant} is read from the RFC 9420 {@code WireFormat}s actually found in the bundle. It
 * is <b>independent of the arm number</b>, and deliberately so: arms 2 and 3 are structurally
 * identical and their type assignment is still partly by elimination
 * ({@link MlsServerMessage.Arm#assignmentIsProven}). Nothing here flips that. What it does give is
 * the two readings ON ONE LINE, so an arm-2 payload whose CONTENT is a Welcome is visible as such
 * the moment it arrives — see {@link #armEvidenceLine()}.
 *
 * <p>The variant list §10.6 enumerates includes a <b>proposal list</b>, and that one is NOT
 * recognised here. A proposal is a {@code PublicMessage} exactly as a Commit is; separating them
 * means decoding {@code FramedContent.content_type}, which sits past the sender variant and the
 * {@code authenticated_data} vector — further into the message than anything the host has ever
 * needed. Reporting {@link Variant#COMMIT} for both is honest; inventing a {@code PROPOSAL} arm we
 * cannot populate would not be.
 *
 * <p>Pure: protobuf and RFC 9420 framing over a byte array. No Android, no engine handle, no
 * transport.
 */
public final class MlsServerBundle {

    private MlsServerBundle() {}

    // ---- descent bounds (carried over verbatim from the host's proven walk) ---------------------

    /** How deep the descent goes. A real subject-change bundle nests at {@code f2.f1.f2.{f1,f3}}. */
    private static final int MAX_DEPTH = 6;
    /** Node budget, so a hostile or malformed payload cannot make the walk expensive. */
    private static final int MAX_CONTAINERS = 512;
    /** Field numbers descended into when looking for sub-containers. */
    private static final int MAX_CONTAINER_FIELD = 16;
    /** Field numbers read when looking for the artifacts themselves. */
    private static final int MAX_ARTIFACT_FIELD = 5;

    /**
     * The log token {@link MlsInvariantScan} looks for when an arm whose type is assigned by
     * ELIMINATION shows up on real traffic.
     *
     * <p>A constant rather than a string typed twice, because the catalogue and the emitter had
     * already drifted: the marker's needle read {@code "6.8 ARM 2/3 OBSERVED"} while the code
     * emitted {@code "6.8 ARM 2 OBSERVED"}, so the scan built to catch this discovery without
     * anyone watching could never have fired on it. Both sides now read this field.
     */
    public static final String ARM_UNPROVEN_MARKER = "6.8 ARM UNPROVEN OBSERVED";

    /**
     * What the bundle was found to CONTAIN — read from the RFC 9420 wire formats inside it, not
     * from the outer arm number.
     */
    public enum Variant {
        /** A {@code Welcome} is present. Its {@code ratchet_tree} is out-of-band, so the WHOLE blob
         *  must reach the joiner: see {@link Recognised#forwardWholeBlob}. */
        WELCOME,
        /** A {@code GroupInfo} is present — the re-drive input. */
        GROUP_INFO,
        /** A {@code PublicMessage} is present: a Commit, or a proposal we do not separate (above). */
        COMMIT,
        /** Only {@code PrivateMessage}s — application traffic, or an in-band key delivery. */
        APPLICATION,
        /** The server acknowledging one of OUR sends. Not MLS: arm 4 carries no MLSMessage at all. */
        ACCEPTED,
        /** Nothing in the blob parses as an {@code MLSMessage}. */
        UNRECOGNISED,
    }

    /** The typed result §10.6 asks the host to read instead of inspecting bytes. */
    public static final class Recognised {

        /** The outer {@code ServerMlsRcsMessage} oneof arm, or {@link MlsServerMessage.Arm#NONE}. */
        public final MlsServerMessage.Arm arm;
        /** The arm's payload, exactly as it appeared. Never null. */
        public final byte[] armPayload;
        /** What the bundle contains, from its wire formats. Independent of {@link #arm}. */
        public final Variant variant;

        /**
         * Every bare {@code MLSMessage} found, in apply order, deduplicated.
         *
         * <p>Order is load-bearing and is the bundle's, not a scheduler's: a metadata commit is TWO
         * messages, the commit at {@code f1} and the key delivery at {@code f3}, and the key is
         * encrypted at the POST-commit epoch. Empty when nothing parsed.
         */
        public final List<byte[]> messages;

        /**
         * Exactly what to hand the MLS owner, in order — {@link #messages}, or the whole blob when
         * {@link #forwardWholeBlob}. The host should use THIS and not re-derive it.
         */
        public final List<byte[]> delivery;

        /**
         * A {@code Welcome} is in the bundle, so the extracted messages must be discarded and the
         * ENTIRE blob forwarded.
         *
         * <p>Our Welcomes are TREE-LESS: create emits the Welcome and the {@code ratchet_tree} as
         * separate fields and the joiner splices them. Forwarding just the Welcome strips the tree
         * and the join is dropped as "no joinable Welcome". Also true when nothing parsed at all, so
         * an unrecognised blob is passed on rather than swallowed.
         */
        public final boolean forwardWholeBlob;

        /** True when the arm-1 payload was taken verbatim and the descent skipped entirely. */
        public final boolean takenVerbatim;

        /** {@code AcceptedMlsRcsMessage.message_id} on arm 4; {@code null} otherwise. */
        public final String acceptedMessageId;

        /**
         * The 32-byte value at the bundle's {@code f4} on arms 2/3, or {@code null}.
         *
         * <p>This is the {@code MlsGroupInfo} bundle's <b>epoch authenticator</b> — the field whose
         * agreement with the response's own anchor field is what settled arm 3
         * ({@link MlsServerMessage.Arm#MLS_GROUP_INFO}). Read here so that the same check can be
         * made on any arm as it arrives, which is the evidence we have been unable to
         * obtain: its 57 arm-2 observations were logged as sizes and the bodies never persisted.
         *
         * <p>EVIDENCE, NOT PROOF, and the distinction matters: both candidate types have a
         * {@code {1,2,3,4}}-bytes schema, so a commit bundle <i>could</i> carry a 32-byte f4 of its
         * own. Read it together with {@link #variant}, which is derived from content.
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
         * Arm 4: a server ACK for one of our sends, not an MLS control message.
         *
         * <p>The host MUST treat this as handled. Feeding it to an engine yields a MALFORMED, and
         * reporting failure sends the peer a plaintext IMDN about a message that was never
         * undelivered — a failure report in answer to the server saying we succeeded.
         */
        public boolean isAccepted() { return arm == MlsServerMessage.Arm.ACCEPTED; }

        /** The one INFO line. One call, one line: the arm and the content, together. */
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
            if (acceptedMessageId != null) sb.append(" acceptedMessageId=").append(acceptedMessageId);
            if (groupInfoEpochAuthenticator != null) {
                sb.append(" f4=").append(groupInfoEpochAuthenticator.length)
                  .append("B (GroupInfo-shaped)");
            }
            return sb.toString();
        }

        /**
         * The WARN line for an arm whose TYPE assignment is by elimination, or {@code null}.
         *
         * <p>Carries {@link #ARM_UNPROVEN_MARKER} so {@link MlsInvariantScan} finds it in a saved
         * capture, and — this is the point — carries the CONTENT reading next to the arm number.
         * The two readings are independent, so they can be compared:
         *
         * <ul>
         *   <li>{@link Variant#WELCOME} or {@link Variant#COMMIT} on arm 2 CORROBORATES the
         *       elimination (arm 2 = {@code ServerCommitBundle}).</li>
         *   <li>{@link Variant#GROUP_INFO}, or a 32-byte {@code f4} with no commit in the bundle,
         *       CONTRADICTS it — and {@link MlsServerMessage.Arm#assignmentIsProven} is the one
         *       place to flip.</li>
         * </ul>
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
     * Recognise a relayed control blob.
     *
     * <p>Order of business, and each step is load-bearing:
     *
     * <ol>
     *   <li><b>Outer arm first.</b> Arm 4 is not MLS at all and never reaches the descent.</li>
     *   <li><b>Arm 1 verbatim.</b> A bytes arm carries the MLSMessage DIRECTLY, so the descent
     *       would find it immediately and then keep collecting competing candidates out of the same
     *       buffer. On arm 1 a scan does not merely misroute, it MISHANDLES.</li>
     *   <li><b>Otherwise descend.</b> Bundles nest — device-decoded at
     *       {@code wire.f2.f1.f2.{f1,f3}} — so the walk is structural and recursive rather than a
     *       hard-coded path, and every child is an exact protobuf length, never a byte pattern.</li>
     *   <li><b>Then classify.</b> The Welcome rule is a consequence of the classification instead
     *       of a special case bolted onto a scan that could not tell what it had.</li>
     * </ol>
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

        // The Welcome test, now a wire-format read rather than four literal bytes. Computed over
        // the SAME list the delivery is built from, including the "nothing parsed" fallback, so the
        // classification and the delivery cannot disagree about what is in the blob.
        final List<byte[]> classifyOver =
                messages.isEmpty() ? Collections.singletonList(blob) : messages;
        final boolean carriesWelcome = contains(classifyOver, MlsWireScan.WF_WELCOME);
        final boolean whole = carriesWelcome || messages.isEmpty();

        return new Recognised(armed.arm, armed.payload, classify(classifyOver), messages,
                whole ? Collections.singletonList(blob) : messages,
                whole, /*takenVerbatim=*/ verbatim != null, /*acceptedMessageId=*/ null, f4);
    }

    // ---- the pieces --------------------------------------------------------------------------

    /**
     * Arm 1's payload, taken as-is — bare or {@code mls_varint}-wrapped — or {@code null} to fall
     * through to the descent.
     *
     * <p>Falling through when the payload does not parse is deliberate and is NOT the same as
     * dropping it: an arm-1 payload we cannot read is not something to discard on the strength of
     * our own parse.
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
     * Every bare {@code MLSMessage} reachable in the blob, in wire order.
     *
     * <p>Breadth-first over exact protobuf length-delimited fields, bounded in depth and in nodes,
     * then a read of the artifact-bearing fields of every container found. Each candidate is tried
     * as-is AND {@code mls_varint}-stripped, because a bundle field holds either form and only one
     * of the two parses.
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
     * First occurrence wins, so the bundle's own order survives.
     *
     * <p>The descent visits the same field from more than one container by construction, so the
     * same Commit is found several times; order matters (the key delivery is encrypted at the
     * post-commit epoch) and so does uniqueness (applying a commit twice is a failure). Keyed on
     * content hash with an equality check, not on a stringified copy of every candidate — these run
     * to 9 KB apiece.
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
     * Content-derived variant.
     *
     * <p>Precedence is by consequence, not by count: a Welcome changes how the blob must be
     * DELIVERED and so outranks everything; a GroupInfo selects the re-drive path; a Commit changes
     * the epoch. A metadata commit carries a PrivateMessage alongside its Commit, so
     * {@link Variant#APPLICATION} means "only private messages", never "some".
     */
    private static Variant classify(final List<byte[]> msgs) {
        if (contains(msgs, MlsWireScan.WF_WELCOME)) return Variant.WELCOME;
        if (contains(msgs, MlsWireScan.WF_GROUP_INFO)) return Variant.GROUP_INFO;
        if (contains(msgs, MlsWireScan.WF_PUBLIC_MESSAGE)) return Variant.COMMIT;
        if (contains(msgs, MlsWireScan.WF_PRIVATE_MESSAGE)) return Variant.APPLICATION;
        return Variant.UNRECOGNISED;
    }
}
