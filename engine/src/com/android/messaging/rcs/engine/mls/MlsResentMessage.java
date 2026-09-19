/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The RCC.16 §10.3 resent-message component in the trailing slot of the application
 * AuthenticatedData, and the MAC over it. {@link MlsAppMessage#buildAuthenticatedData} emits
 * {@code 00 01 | varint(len) | message_id | era u32 | <trailing>}; the trailing slot is
 * {@code 0x00} for every ordinary message.
 *
 * <pre>
 *   tag = HMAC-SHA256(key = hmac_field[0 .. 32], data = the original message id)
 *   accept iff tag == hmac_field[32 .. 64]
 * </pre>
 *
 * <p>There is no key derivation, and the key travels in the clear, so the MAC passes for every
 * member and does not select a recipient. {@link #parse} models the present form as a tag and one
 * length-prefixed opaque; the real structure has four fields (a u32 and three {@code mls_varint}
 * opaques, the last the 64-byte HMAC field) behind unresolved outer framing, so a real resend may
 * parse as malformed. See docs/mls/health-and-recovery.md.
 */
public final class MlsResentMessage {

    private MlsResentMessage() {}

    /** The trailing slot when there is no resent-message component. */
    public static final int TAG_ABSENT = 0x00;

    /**
     * The resent-message variant, {@code 0x02}; inferred, not established. A peer rejecting it with
     * {@link #ERR_NOT_PRESENT} means the tag is wrong; a parse error means the prefix width is.
     */
    public static final int TAG_RESENT = 0x02;

    /** Other clients' rejection when the component is missing; the string a wrong tag produces. */
    public static final String ERR_NOT_PRESENT =
            "Authenticated data struct for resent message does not contain a resent message";

    /** The HMAC field is exactly this long. */
    public static final int HMAC_FIELD_LEN = 64;
    /** {@code [0..32)} is the key, in the clear. */
    public static final int HMAC_KEY_LEN = 32;

    /** Which length-prefix encoding parsed; {@link #UNKNOWN} when the payload is empty. */
    public enum PrefixWidth { U8, U16, VARINT, UNKNOWN }

    /** One decoded component. */
    public static final class Parsed {
        public final int tag;
        public final byte[] payload;
        /** Which prefix encoding produced {@link #payload}. */
        public final PrefixWidth width;
        /** Whether more than one candidate width parsed the same bytes. */
        public final boolean ambiguous;

        Parsed(final int tag, final byte[] payload, final PrefixWidth width,
                final boolean ambiguous) {
            this.tag = tag;
            this.payload = payload;
            this.width = width;
            this.ambiguous = ambiguous;
        }

        public boolean present() { return tag != TAG_ABSENT; }

        @Override public String toString() {
            return "resent{tag=0x" + Integer.toHexString(tag)
                    + " payload=" + (payload == null ? 0 : payload.length) + "B"
                    + " width=" + width + (ambiguous ? " AMBIGUOUS" : "") + "}";
        }
    }

    /**
     * Decodes the trailing component. Fields inside the resent-message struct use
     * {@code mls_varint} (RFC 9420 §2.1.2), but the outer framing is unresolved, so every candidate
     * width is tried and the match reported; {@link Parsed#ambiguous} is set when more than one
     * fits.
     *
     * @return the component, or null if the slot is malformed
     */
    public static Parsed parse(final byte[] tail) {
        if (tail == null || tail.length == 0) return null;
        final int tag = tail[0] & 0xFF;
        if (tag == TAG_ABSENT) {
            // Absent is a bare tag; anything after it is not ours to interpret.
            return new Parsed(TAG_ABSENT, new byte[0], PrefixWidth.UNKNOWN, false);
        }
        final byte[] rest = Arrays.copyOfRange(tail, 1, tail.length);
        final List<PrefixWidth> matched = new ArrayList<>();
        byte[] first = null;
        for (final PrefixWidth w : new PrefixWidth[] {
                PrefixWidth.U8, PrefixWidth.U16, PrefixWidth.VARINT }) {
            final byte[] p = readOpaque(rest, w);
            if (p != null) {
                matched.add(w);
                if (first == null) first = p;
            }
        }
        if (matched.isEmpty()) return null;
        return new Parsed(tag, first, matched.get(0), matched.size() > 1);
    }

    /** Reads a length-prefixed opaque that consumes {@code b} exactly, or null. */
    private static byte[] readOpaque(final byte[] b, final PrefixWidth w) {
        if (b == null || b.length == 0) return null;
        final int len;
        final int off;
        switch (w) {
            case U8:
                len = b[0] & 0xFF;
                off = 1;
                break;
            case U16:
                if (b.length < 2) return null;
                len = ((b[0] & 0xFF) << 8) | (b[1] & 0xFF);
                off = 2;
                break;
            case VARINT: {
                final int first = b[0] & 0xFF;
                final int n = 1 << (first >>> 6);
                if (n > 4 || b.length < n) return null;
                int v = first & 0x3F;
                for (int i = 1; i < n; i++) v = (v << 8) | (b[i] & 0xFF);
                len = v;
                off = n;
                break;
            }
            default:
                return null;
        }
        // Exact consumption only; a prefix that merely fits would let every width match.
        if (len < 0 || off + len != b.length) return null;
        return Arrays.copyOfRange(b, off, off + len);
    }

    /** The outcome of checking a resend's MAC field. */
    public enum MacVerdict {
        /** The MAC matched. */
        FOR_ME,
        /** The MAC did not match. Benign; never a reason to send a negative receipt. */
        NOT_FOR_ME,
        /** The field is present but not {@link #HMAC_FIELD_LEN} bytes. */
        INVALID_LENGTH,
        /** The field is empty. */
        EMPTY
    }

    /**
     * Classifies a resend's MAC field. A mismatch is {@link MacVerdict#NOT_FOR_ME}, never a
     * failure: losing one message to the ordinary FTD path is cheaper than a negative receipt for a
     * healthy resend from every non-target member.
     *
     * @param hmacField the 64-byte field: {@code [0..32)} key, {@code [32..64)} tag
     * @param macdData  the data to MAC
     */
    public static MacVerdict classify(final byte[] hmacField, final byte[] macdData) {
        if (hmacField == null || hmacField.length == 0) return MacVerdict.EMPTY;
        if (hmacField.length != HMAC_FIELD_LEN) return MacVerdict.INVALID_LENGTH;
        final byte[] key = Arrays.copyOfRange(hmacField, 0, HMAC_KEY_LEN);
        final byte[] want = Arrays.copyOfRange(hmacField, HMAC_KEY_LEN, HMAC_FIELD_LEN);
        final byte[] got = mac(key, macdData == null ? new byte[0] : macdData);
        if (got == null) return MacVerdict.NOT_FOR_ME;
        // Constant-time compare.
        return MessageDigest.isEqual(got, want) ? MacVerdict.FOR_ME : MacVerdict.NOT_FOR_ME;
    }

    /** Which candidate MAC input matched, if any. */
    public static final class Selection {
        public final MacVerdict verdict;
        /** The name of the candidate that matched, or null. */
        public final String candidate;

        Selection(final MacVerdict v, final String c) { this.verdict = v; this.candidate = c; }

        @Override public String toString() {
            return verdict + (candidate == null ? "" : " via '" + candidate + "'");
        }
    }

    /**
     * Tries a named set of candidate MAC inputs and reports which matched, so the input identifies
     * itself on live traffic. No match is {@link MacVerdict#NOT_FOR_ME}.
     */
    public static Selection select(final byte[] hmacField,
            final java.util.Map<String, byte[]> candidates) {
        if (hmacField == null || hmacField.length == 0) {
            return new Selection(MacVerdict.EMPTY, null);
        }
        if (hmacField.length != HMAC_FIELD_LEN) {
            return new Selection(MacVerdict.INVALID_LENGTH, null);
        }
        if (candidates != null) {
            for (final java.util.Map.Entry<String, byte[]> e : candidates.entrySet()) {
                if (classify(hmacField, e.getValue()) == MacVerdict.FOR_ME) {
                    return new Selection(MacVerdict.FOR_ME, e.getKey());
                }
            }
        }
        return new Selection(MacVerdict.NOT_FOR_ME, null);
    }

    /** {@code HMAC-SHA256(key, data)}, or null if the platform lacks it. */
    public static byte[] mac(final byte[] key, final byte[] data) {
        try {
            final Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return m.doFinal(data);
        } catch (final Exception impossible) {
            return null;
        }
    }

    /**
     * Encodes the component; the caller must state the prefix width ({@link PrefixWidth#VARINT}).
     * Not wired into the send path: the u32 field's value, the roles of the two middle fields and
     * the outer framing are all unknown.
     */
    public static byte[] encode(final int tag, final byte[] payload, final PrefixWidth width) {
        if (width == null || width == PrefixWidth.UNKNOWN) {
            throw new IllegalArgumentException(
                    "the resent-message length-prefix width is not established — state it explicitly "
                            + "(u8/u16/varint) rather than taking a default that would be a guess");
        }
        final byte[] p = payload == null ? new byte[0] : payload;
        final byte[] pre;
        switch (width) {
            case U8:
                pre = new byte[] { (byte) p.length };
                break;
            case U16:
                pre = new byte[] { (byte) (p.length >>> 8), (byte) p.length };
                break;
            default:
                pre = MlsAppMessage.mlsVarint(p.length);
                break;
        }
        final byte[] out = new byte[1 + pre.length + p.length];
        out[0] = (byte) tag;
        System.arraycopy(pre, 0, out, 1, pre.length);
        System.arraycopy(p, 0, out, 1 + pre.length, p.length);
        return out;
    }
}
