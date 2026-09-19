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

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The rendezvous record — "have we already decrypted this message?" (rework item 6.4, invariant 52).
 *
 * <p>The bug this closes is a real one and is not rare. Our inbound path calls straight into the
 * destructive engine op with no prior lookup: decrypt → {@code process_incoming_message} →
 * {@code write_to_storage}, with no dedup by RCS message id anywhere between them. So <b>any</b>
 * Tachyon re-delivery, any duplicate, or a process death between the decrypt and the chat-row insert
 * re-runs a <b>ratchet-advancing</b> decrypt.
 *
 * <p>Re-running is not merely wasted work. The MLS application ratchet advances per message, so a
 * second decrypt of the same ciphertext consumes a key the sender will never re-use, and the
 * conversation drifts by one generation each time it happens.
 *
 * <h2>Read before decrypt, write with the result, delete on chat-row insert</h2>
 *
 * <p>Three steps and the asymmetry between the first and third is deliberate:
 *
 * <ul>
 *   <li><b>Read</b> keyed on {@code (self_identity, remote_user_id, rcs_message_id, stage)} — narrow,
 *       because a hit must mean "this exact message, from this exact peer, at this exact stage".</li>
 *   <li><b>Write</b> the result so a duplicate can be answered by replay instead of by decrypting
 *       again.</li>
 *   <li><b>Delete</b> keyed WITHOUT {@code remote_user_id} — deliberately BROADER than the read.
 *       Once the message has a chat row, every stage and every sender-attribution of it is settled,
 *       and leaving siblings behind would keep rows alive that nothing will ever read again.</li>
 * </ul>
 *
 * <p>Getting the delete key wrong in the narrow direction is the failure worth naming: it looks
 * correct, it passes every duplicate test, and it leaks a row per message forever.
 */
public final class MlsRendezvous {

    /**
     * Which stage of processing produced the stored result.
     *
     * <p>Part of the READ key: the same message id legitimately passes through more than one stage,
     * and a hit from the wrong stage would replay the wrong answer.
     */
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
     * The READ key — narrow.
     *
     * <p>Field separator is {@code "\0"}: an E.164 cannot contain it and neither can a message
     * id, so two different tuples cannot collide by concatenation. A {@code "|"} separator would
     * make {@code ("a|b", "c")} and {@code ("a", "b|c")} the same key, which is the classic way a
     * dedup table starts answering the wrong question.
     */
    public static String readKey(final String selfIdentity, final String remoteUserId,
            final String rcsMessageId, final Stage stage) {
        return norm(selfIdentity) + "\0" + norm(remoteUserId) + "\0"
                + norm(rcsMessageId) + "\0"
                + (stage == null ? Stage.UNKNOWN : stage).wire;
    }

    /**
     * The DELETE prefix — broader, and deliberately excludes {@code remote_user_id} and the stage.
     *
     * <p>See the class doc: once the message has a chat row, every stage and every
     * sender-attribution of it is settled.
     */
    public static String deletePrefix(final String selfIdentity, final String rcsMessageId) {
        return norm(selfIdentity) + "\0";
    }

    /**
     * Does {@code key} belong to {@code (selfIdentity, rcsMessageId)}, whatever the sender or stage?
     *
     * <p>A prefix test cannot express this on its own, because {@code remote_user_id} sits BETWEEN
     * the two fields being matched. So the match is on field positions rather than on a string
     * prefix — which is precisely why the delete is a scan and not a point lookup, and why it is
     * worth doing only at chat-row insert rather than on every message.
     */
    public static boolean keyMatchesMessage(final String key, final String selfIdentity,
            final String rcsMessageId) {
        if (key == null) return false;
        final String[] parts = key.split("\0", -1);
        if (parts.length < 4) return false;
        return parts[0].equals(norm(selfIdentity)) && parts[2].equals(norm(rcsMessageId));
    }

    /** A stored result: what the engine said, so a duplicate is answered by replay. */
    public static final class Stored {
        /** The engine {@code processEx} status — see {@link MlsProcStatus}. */
        public final int status;
        /** The decrypted payload, or empty. */
        private final byte[] mPayload;
        /** When it was stored, ms since epoch — diagnostic only, never a staleness input. */
        public final long storedAtMs;

        public Stored(final int status, final byte[] payload, final long storedAtMs) {
            this.status = status;
            mPayload = payload == null ? new byte[0] : copy(payload);
            this.storedAtMs = storedAtMs;
        }

        public byte[] payload() { return copy(mPayload); }

        /**
         * Encode for the store: {@code status "\0" storedAtMs "\0" base64(payload)}.
         *
         * <p>Text rather than bytes because the backing store is SharedPreferences, matching how
         * {@link MlsConversationRecord} is persisted today.
         */
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
                // A corrupt row must read as ABSENT, never as a partial hit: replaying a
                // half-decoded result is worse than decrypting again.
                return null;
            }
        }

        @Override public String toString() {
            return "rendezvous{" + MlsProcStatus.nameOf(status)
                    + " payload=" + mPayload.length + "B at=" + storedAtMs + "}";
        }
    }

    // -- base64, local so the engine module keeps depending on nothing -----------------------------

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

    /** Unused but kept honest: the charset every id above is normalised in. */
    static final java.nio.charset.Charset CHARSET = StandardCharsets.UTF_8;

    private MlsRendezvous() {}
}
