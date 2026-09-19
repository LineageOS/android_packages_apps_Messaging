/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Stored results of inbound processing, keyed by message, so a re-delivered or duplicated message
 * is answered by replay instead of a second, ratchet-advancing decrypt. Read before decrypt, write
 * with the result, delete when the chat row is inserted. The delete matches every sender and stage
 * of the message, deliberately broader than the read; a narrower delete leaks a row per message.
 */
public final class MlsRendezvous {

    /** Which stage produced the stored result; part of the read key. */
    public enum Stage {
        UNKNOWN(0),
        /** The application-message decrypt. */
        DECRYPT(1),
        /** An inbound control apply (commit, proposal, Welcome). */
        CONTROL(2),
        /** A re-drive or pending-queue drain replaying earlier work. */
        REDRIVE(3);

        public final int wire;

        Stage(final int wire) { this.wire = wire; }

        public static Stage fromWire(final int w) {
            for (final Stage s : values()) if (s.wire == w) return s;
            return UNKNOWN;
        }
    }

    /**
     * The read key. Fields are separated by {@code "\0"}, which neither an E.164 nor a message id
     * can contain, so distinct tuples cannot collide by concatenation.
     */
    public static String readKey(final String selfIdentity, final String remoteUserId,
            final String rcsMessageId, final Stage stage) {
        return norm(selfIdentity) + "\0" + norm(remoteUserId) + "\0"
                + norm(rcsMessageId) + "\0"
                + (stage == null ? Stage.UNKNOWN : stage).wire;
    }

    /**
     * The delete prefix: our identity only. {@link #keyMatchesMessage} narrows it to the message.
     */
    public static String deletePrefix(final String selfIdentity, final String rcsMessageId) {
        return norm(selfIdentity) + "\0";
    }

    /**
     * Whether {@code key} belongs to {@code (selfIdentity, rcsMessageId)}, whatever the sender or
     * stage. Matched by field position, since the sender sits between the two fields.
     */
    public static boolean keyMatchesMessage(final String key, final String selfIdentity,
            final String rcsMessageId) {
        if (key == null) return false;
        final String[] parts = key.split("\0", -1);
        if (parts.length < 4) return false;
        return parts[0].equals(norm(selfIdentity)) && parts[2].equals(norm(rcsMessageId));
    }

    /** A stored result, so a duplicate is answered by replay. */
    public static final class Stored {
        /** The engine {@code processEx} status; see {@link MlsProcStatus}. */
        public final int status;
        /** The decrypted payload, or empty. */
        private final byte[] mPayload;
        /** When it was stored, ms since epoch; diagnostic only. */
        public final long storedAtMs;

        public Stored(final int status, final byte[] payload, final long storedAtMs) {
            this.status = status;
            mPayload = payload == null ? new byte[0] : copy(payload);
            this.storedAtMs = storedAtMs;
        }

        public byte[] payload() { return copy(mPayload); }

        /** Encodes as {@code status "\0" storedAtMs "\0" base64(payload)}. */
        public String encode() {
            return status + "\0" + storedAtMs + "\0" + base64(mPayload);
        }

        /** @return the decoded record, or {@code null} if {@code s} is not one */
        public static Stored decode(final String s) {
            if (s == null) return null;
            final String[] p = s.split("\0", -1);
            if (p.length < 3) return null;
            try {
                return new Stored(Integer.parseInt(p[0]), unbase64(p[2]), Long.parseLong(p[1]));
            } catch (final RuntimeException notARecord) {
                // A corrupt row reads as absent, never as a partial hit.
                return null;
            }
        }

        @Override public String toString() {
            return "rendezvous{" + MlsProcStatus.nameOf(status)
                    + " payload=" + mPayload.length + "B at=" + storedAtMs + "}";
        }
    }

    // Local base64 so the engine module keeps no dependencies.

    private static final char[] B64 =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    static String base64(final byte[] in) {
        if (in == null || in.length == 0) return "";
        final StringBuilder sb = new StringBuilder(((in.length + 2) / 3) * 4);
        for (int i = 0; i < in.length; i += 3) {
            final int b0 = in[i] & 0xFF;
            final int b1 = (i + 1 < in.length) ? in[i + 1] & 0xFF : 0;
            final int b2 = (i + 2 < in.length) ? in[i + 2] & 0xFF : 0;
            sb.append(B64[b0 >>> 2]);
            sb.append(B64[((b0 & 0x03) << 4) | (b1 >>> 4)]);
            sb.append(i + 1 < in.length ? B64[((b1 & 0x0F) << 2) | (b2 >>> 6)] : '=');
            sb.append(i + 2 < in.length ? B64[b2 & 0x3F] : '=');
        }
        return sb.toString();
    }

    static byte[] unbase64(final String s) {
        if (s == null || s.isEmpty()) return new byte[0];
        final int[] rev = new int[128];
        for (int i = 0; i < rev.length; i++) rev[i] = -1;
        for (int i = 0; i < B64.length; i++) rev[B64[i]] = i;
        int pad = 0;
        for (int i = s.length() - 1; i >= 0 && s.charAt(i) == '='; i--) pad++;
        final int chars = s.length() - pad;
        final byte[] out = new byte[Math.max(0, chars * 6 / 8)];
        int acc = 0, bits = 0, o = 0;
        for (int i = 0; i < chars; i++) {
            final char c = s.charAt(i);
            final int v = (c < 128) ? rev[c] : -1;
            if (v < 0) throw new IllegalArgumentException("not base64");
            acc = (acc << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                if (o < out.length) out[o++] = (byte) ((acc >>> bits) & 0xFF);
            }
        }
        return out;
    }

    private static String norm(final String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static byte[] copy(final byte[] b) {
        final byte[] out = new byte[b.length];
        System.arraycopy(b, 0, out, 0, b.length);
        return out;
    }

    /** The charset ids are normalised in; unused. */
    static final java.nio.charset.Charset CHARSET = StandardCharsets.UTF_8;

    private MlsRendezvous() {}
}
