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

/**
 * {@code ServerMlsRcsMessage}'s outer oneof — rework item {@code 6.8}, §10.6.
 *
 * <h2>The one place §10.6 says the host MUST inspect a variant</h2>
 *
 * <p>§10.6's structural instruction is <i>"never switch on the bundle variant"</i> — hand the bytes
 * over and read the typed result. But it carries a qualification that, taken absolutely, deletes two
 * real host branches: <b>the host DOES select the outer arm, twice.</b> It tests arm <b>4</b>
 * ({@code AcceptedMlsRcsMessage}) to choose a group-resolution strategy, and it selects the
 * GroupInfo arm on the re-drive path. <i>"An implementer told 'never inspect the variant' will not
 * build the accepted-message branch."</i>
 *
 * <p>This is that outer select, and <b>only</b> that. It does not decode bundle contents; the inner
 * variants remain the engine's business.
 *
 * <h2>TWO NESTED ONEOFS — do not conflate them</h2>
 *
 * <p>The numbers here are one level BELOW the ones §10.6 records, and mixing them is the first
 * mistake available:
 *
 * <pre>
 * ProcessMessageRequest {          // the OUTER oneof — §10.6's "2 raw / 5 server / 8 keys / 10 groupInfo"
 *   oneof { bytes raw = 2; ServerMlsRcsMessage server = 5; ... keys = 8; ... groupInfo = 10; }
 * }
 * ServerMlsRcsMessage {            // the type AT field 5, with its OWN 4-arm oneof — THIS class
 *   oneof { bytes = 1; ? = 2; ? = 3; AcceptedMlsRcsMessage = 4; }
 * }
 * </pre>
 *
 * <h2>Arms 2 and 3 — RESOLVED 2026-08-06, after being inseparable for weeks</h2>
 *
 * <p>The two remaining arms are {@code ServerCommitBundle} and {@code MlsGroupInfo}, and for a long
 * time which-is-which was <b>not statically determinable</b>: both types are structurally
 * identical — {@code {1: bytes, 2: bytes, 3: bytes, 4: bytes}}, same field count, numbering, types
 * and schema string — with no name string for either anywhere in the app. A 50/50 guess would have
 * been worse than useless, because both arms parse against either interpretation: a wrong
 * assignment would not fail loudly, it would mis-route commit bundles into the GroupInfo re-drive
 * path <i>and look like it worked</i>.
 *
 * <p>It was settled from the wire, by a <b>value</b> agreement rather than a shape match — which is
 * why it counts. Arm 3's wire type is field 4 of the
 * {@code GetMlsGroupInfo} response, and our own decode of it reads {@code f1} = the GroupInfo,
 * {@code f2} = the ratchet tree, and <b>{@code f4} = the 32-byte epoch authenticator, which equals
 * the response's own anchor field</b>. That anchor is read by a completely independent path (the era/epoch
 * check), and a commit bundle has no reason to carry the current GroupInfo's epoch authenticator.
 * Two structurally identical candidates cannot both satisfy it.
 *
 * <p>So {@link Arm#MLS_GROUP_INFO} is <b>proven</b> and {@link Arm#SERVER_COMMIT_BUNDLE} follows by
 * <b>elimination</b> — a real distinction, preserved in {@link Arm#assignmentIsProven()} rather than
 * flattened away by the naming. Routing never consults it; the arm NUMBERS are proven stable
 * across the FFI/wire boundary by the converter between the internal and wire types, which maps
 * them position-for-position.
 *
 * <p><b>Arm 1 is the fourth arm</b> that could never be accounted for from three named messages: it
 * is a bare BYTES arm, not a message type. It is also the arm a byte-scanning heuristic silently
 * mishandles rather than merely misroutes — the scan finds a valid MLSMessage immediately and then
 * keeps collecting competing candidates from the same buffer.
 *
 * <p>Pure: a parse over bytes, no Android, no engine handle.
 */
public final class MlsServerMessage {

    private MlsServerMessage() {}

    /** Which arm of {@code ServerMlsRcsMessage}'s oneof was present. */
    public enum Arm {
        /**
         * Field 1 — a RAW BYTES arm, and the fourth arm that could not be accounted for from the
         * three named messages.
         *
         * <p>It is not one of them: it carries no message class in the schema's object table, and
         * its field type differs from arms 2-4 exactly as {@code ProcessMessageRequest}'s own "raw"
         * arm differs from its message arms. Structurally parallel to the raw arm one level up.
         */
        RAW(1),
        /**
         * Field 2 — {@code ServerCommitBundle}.
         *
         * <p><b>By elimination, not by observation</b>, and that distinction is preserved in
         * {@link #assignmentIsProven()} rather than being flattened into this name. Arm 3 is
         * positively identified; this is "the other one".
         */
        SERVER_COMMIT_BUNDLE(2),
        /**
         * Field 3 — {@code MlsGroupInfo}. <b>Established by observation.</b>
         *
         * <p>Resolved from wire data we were already decoding, after weeks of the
         * two arms being structurally inseparable (they have byte-identical
         * schemas — {1,2,3,4} all bytes, same hasbits — and no name string exists for either
         * anywhere in the app).
         *
         * <p>What settled it is a <b>value</b> agreement, not a shape match, which is why it counts
         * as proof: arm 3's wire type is field 4 of the {@code GetMlsGroupInfo} response,
         * and our decode of it reads
         *
         * <pre>
         *   f1 = MLSMessage wire_format 4 (the GroupInfo itself)
         *   f2 = mls_varint-prefixed ratchet_tree
         *   f4 = the 32B epoch authenticator — WHICH EQUALS THE RESPONSE'S OWN ANCHOR FIELD
         * </pre>
         *
         * <p>That last line is the whole argument. That anchor is the slot our
         * era/epoch check reads by a completely independent path, and a commit bundle has no reason
         * to carry the CURRENT GroupInfo's epoch authenticator. Two structurally identical
         * candidates cannot both satisfy it. Corroborated twice more: arm 3's wire type living
         * inside the GroupInfo response at all, and its internal twin being carried by the
         * advance-era request — the same carry an era advance passes down.
         */
        MLS_GROUP_INFO(3),
        /** Field 4 — {@code AcceptedMlsRcsMessage}. The arm §10.6 requires the host to test. */
        ACCEPTED(4),
        /** No recognised arm was present. */
        NONE(0);

        /** The oneof field number. In proto, the oneof case value IS the field number. */
        public final int field;

        Arm(final int field) { this.field = field; }

        /**
         * Is this arm's TYPE assignment established by observation, or reached by elimination?
         *
         * <p>The single place the 2-vs-3 uncertainty lives, kept deliberately after the confirmation
         * came back positive rather than deleted with it. The rule is to build the switch on
         * the NUMBERS — which are established arm-for-arm by the converter between the internal
         * type and its wire twin — and keep the semantic behind one named
         * mapping that can be flipped in one place. That costs nothing and is the difference between
         * a one-line change and a hunt, if the elimination ever turns out to be wrong.
         *
         * <p>Routing must never depend on this. It is for the log line and for whoever reads it.
         */
        public boolean assignmentIsProven() {
            // Arm 3 by the epoch-authenticator agreement; arm 4 by its own distinct shape
            // ({1: string, 2: an opaque type}); arm 1 structurally, as the schema's only bytes arm.
            return this != SERVER_COMMIT_BUNDLE;
        }

        public static Arm forField(final int field) {
            for (final Arm a : values()) {
                if (a != NONE && a.field == field) return a;
            }
            return NONE;
        }
    }

    /** The parsed outer envelope: which arm, and its raw payload. */
    public static final class Parsed {
        public final Arm arm;
        /** The arm's bytes, exactly as they appeared. Never null; empty when {@link Arm#NONE}. */
        public final byte[] payload;

        Parsed(final Arm arm, final byte[] payload) {
            this.arm = arm;
            this.payload = payload == null ? new byte[0] : payload;
        }

        /** Whether this is the accepted-message arm §10.6 requires the host to test. */
        public boolean isAccepted() { return arm == Arm.ACCEPTED; }

        @Override public String toString() {
            return "ServerMlsRcsMessage{" + arm + " field=" + arm.field
                    + " " + payload.length + "B}";
        }
    }

    /**
     * Parse the outer oneof.
     *
     * <p>Takes the FIRST recognised arm. A oneof carries at most one arm by construction, and a
     * message with two is malformed — taking the first is what a proto reader does, and inventing a
     * "both present" error here would be a rule Google Messages does not have.
     *
     * @return never null; {@link Arm#NONE} when nothing recognisable was present
     */
    public static Parsed parse(final byte[] b) {
        if (b == null || b.length == 0) return new Parsed(Arm.NONE, null);
        int i = 0;
        while (i < b.length) {
            final long[] tag = readVarint(b, i);
            if (tag == null) break;
            i = (int) tag[1];
            final int field = (int) (tag[0] >>> 3);
            final int wireType = (int) (tag[0] & 7);
            if (wireType != 2) {                    // every arm here is length-delimited
                final int skipped = skip(b, i, wireType);
                if (skipped < 0) break;
                i = skipped;
                continue;
            }
            final long[] len = readVarint(b, i);
            if (len == null) break;
            i = (int) len[1];
            final int n = (int) len[0];
            if (n < 0 || i + n > b.length) break;
            final Arm arm = Arm.forField(field);
            if (arm != Arm.NONE) {
                final byte[] payload = new byte[n];
                System.arraycopy(b, i, payload, 0, n);
                return new Parsed(arm, payload);
            }
            i += n;                                  // an unknown field: skip it, keep looking
        }
        return new Parsed(Arm.NONE, null);
    }

    /**
     * {@code AcceptedMlsRcsMessage.message_id} — field 1, a string. The native type
     * string is verbatim
     * {@code "…zinnia.common.proto.AcceptedMlsRcsMessage.message_id"}.
     *
     * <p>This is what makes the arm-4 test useful rather than merely a classification: §10.6 says
     * the host tests this arm to choose a group-resolution strategy, and the id names WHICH message
     * was accepted, which is the input that strategy needs.
     *
     * <p>Field 2 is an opaque type and is deliberately not decoded — nothing here needs it, and
     * decoding a type we have no use for would be inventing a contract.
     *
     * @param acceptedArmPayload the {@link Arm#ACCEPTED} arm's bytes
     * @return the message id, or {@code null} if absent
     */
    public static String acceptedMessageId(final byte[] acceptedArmPayload) {
        if (acceptedArmPayload == null) return null;
        final byte[] b = acceptedArmPayload;
        int i = 0;
        while (i < b.length) {
            final long[] tag = readVarint(b, i);
            if (tag == null) return null;
            i = (int) tag[1];
            final int field = (int) (tag[0] >>> 3);
            final int wireType = (int) (tag[0] & 7);
            if (field == 1 && wireType == 2) {
                final long[] len = readVarint(b, i);
                if (len == null) return null;
                i = (int) len[1];
                final int n = (int) len[0];
                if (n < 0 || i + n > b.length) return null;
                return new String(b, i, n, java.nio.charset.StandardCharsets.UTF_8);
            }
            final int skipped = (wireType == 2) ? skipLenDelimited(b, i) : skip(b, i, wireType);
            if (skipped < 0) return null;
            i = skipped;
        }
        return null;
    }

    // ---- minimal protobuf reading ------------------------------------------------------------

    /** @return {@code {value, nextOffset}} or null if truncated/overlong. */
    private static long[] readVarint(final byte[] b, final int from) {
        long v = 0;
        int shift = 0;
        int i = from;
        while (i < b.length && shift < 64) {
            final int c = b[i++] & 0xFF;
            v |= (long) (c & 0x7F) << shift;
            if ((c & 0x80) == 0) return new long[] { v, i };
            shift += 7;
        }
        return null;
    }

    private static int skipLenDelimited(final byte[] b, final int from) {
        final long[] len = readVarint(b, from);
        if (len == null) return -1;
        final int end = (int) (len[1] + len[0]);
        return (len[0] < 0 || end > b.length) ? -1 : end;
    }

    private static int skip(final byte[] b, final int from, final int wireType) {
        switch (wireType) {
            case 0: {
                final long[] v = readVarint(b, from);
                return v == null ? -1 : (int) v[1];
            }
            case 1: return from + 8 <= b.length ? from + 8 : -1;
            case 2: return skipLenDelimited(b, from);
            case 5: return from + 4 <= b.length ? from + 4 : -1;
            default: return -1;                      // groups: not used here
        }
    }
}
