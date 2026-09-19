/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;
/**
 * The outer oneof of {@code ServerMlsRcsMessage}, the message at field 5 of
 * {@code ProcessMessageRequest}. The host selects this arm in two places: the accepted-message arm
 * chooses a group-resolution strategy, and the GroupInfo arm feeds the re-drive path. Inner
 * variants stay the engine's business. Pure: no Android, no engine handle.
 *
 * <pre>
 * ProcessMessageRequest { oneof { raw = 2; server = 5; keys = 8; groupInfo = 10; } }
 * ServerMlsRcsMessage   { oneof { bytes = 1; ServerCommitBundle = 2; MlsGroupInfo = 3;
 *                                 AcceptedMlsRcsMessage = 4; } }
 * </pre>
 */
public final class MlsServerMessage {

    private MlsServerMessage() {}

    /** Which arm of {@code ServerMlsRcsMessage}'s oneof was present. */
    public enum Arm {
        /** Field 1: a bare bytes arm, not a message type. */
        RAW(1),
        /**
         * Field 2: {@code ServerCommitBundle}, assigned by elimination because arms 2 and 3 have
         * identical schemas. See {@link #assignmentIsProven()}.
         */
        SERVER_COMMIT_BUNDLE(2),
        /**
         * Field 3: {@code MlsGroupInfo}. Identified by value: its {@code f4} is the 32-byte epoch
         * authenticator and equals the {@code GetMlsGroupInfo} response's own anchor field
         * ({@code f1} is the GroupInfo, {@code f2} the ratchet tree).
         */
        MLS_GROUP_INFO(3),
        /** Field 4: {@code AcceptedMlsRcsMessage}. */
        ACCEPTED(4),
        /** No recognised arm was present. */
        NONE(0);

        /** The oneof field number, which is also the oneof case value. */
        public final int field;

        Arm(final int field) { this.field = field; }

        /**
         * Whether this arm's type was identified directly rather than by elimination. For log lines
         * only; routing uses the field numbers and must never depend on this.
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

        /** Whether this is the accepted-message arm. */
        public boolean isAccepted() { return arm == Arm.ACCEPTED; }

        @Override public String toString() {
            return "ServerMlsRcsMessage{" + arm + " field=" + arm.field
                    + " " + payload.length + "B}";
        }
    }

    /**
     * Parse the outer oneof, taking the first recognised arm as a proto reader does.
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
     * {@code AcceptedMlsRcsMessage.message_id} (field 1, a string): which message was accepted, the
     * input to the group-resolution strategy. Field 2 is opaque and not decoded.
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
