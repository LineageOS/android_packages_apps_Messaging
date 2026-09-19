/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;
/**
 * RFC 9420 framing reads over raw bytes: locating a bare {@code MLSMessage} inside a larger blob,
 * and reading its wire format, epoch, sender type and content type without decrypting.
 */
public final class MlsWireScan {

    private MlsWireScan() { }

    /**
     * How far the fallback prefix scan looks: a bare message sits at most a few bytes behind a
     * short framing prefix, and a longer scan could false-match inside certificate bytes.
     */
    private static final int FRAMING_PREFIX_MAX = 3;

    /** The first Welcome in {@code b}. */
    public static byte[] findWelcome(final byte[] b) {
        return findMlsMessage(b, 0x00, 0x01, 0x00, 0x03);
    }

    // ---- RFC 9420 WireFormat.

    public static final int WF_PUBLIC_MESSAGE = 1;
    public static final int WF_PRIVATE_MESSAGE = 2;
    public static final int WF_WELCOME = 3;
    public static final int WF_GROUP_INFO = 4;
    public static final int WF_KEY_PACKAGE = 5;

    /**
     * The highest wire format accepted as an MLSMessage. RFC 9420 defines 1 to 5; 6 is also
     * accepted, as the inbound path always has.
     */
    private static final int WF_MAX_ACCEPTED = 6;

    /** Name of a wire format, for logs. */
    public static String wireFormatName(final int wf) {
        switch (wf) {
            case WF_PUBLIC_MESSAGE: return "PublicMessage";
            case WF_PRIVATE_MESSAGE: return "PrivateMessage";
            case WF_WELCOME: return "Welcome";
            case WF_GROUP_INFO: return "GroupInfo";
            case WF_KEY_PACKAGE: return "KeyPackage";
            default: return "unknown(" + wf + ")";
        }
    }

    /** Whether {@code b} begins with an MLSMessage header of an accepted wire format. */
    public static boolean isMlsMessage(final byte[] b) {
        final int wf = wireFormatOf(b);
        return wf >= WF_PUBLIC_MESSAGE && wf <= WF_MAX_ACCEPTED;
    }

    /**
     * Strips a leading {@code mls_varint} length prefix, the inverse of
     * {@link MlsAppMessage#mlsVarint}. Strict: the value must equal the remaining length exactly,
     * because callers try both the bare and the wrapped reading of the same bytes.
     *
     * @return the wrapped body, or {@code null} if {@code b} is not varint-length-wrapped
     */
    public static byte[] stripMlsVarint(final byte[] b) {
        if (b == null || b.length == 0) return null;
        final int first = b[0] & 0xFF;
        final int prefixLen = 1 << (first >>> 6);  // top 2 bits: 00->1B, 01->2B, 10->4B, 11->8B
        if (b.length < prefixLen) return null;
        long v = first & 0x3F;
        for (int i = 1; i < prefixLen; i++) v = (v << 8) | (b[i] & 0xFF);
        if (v != (long) (b.length - prefixLen)) return null;
        return java.util.Arrays.copyOfRange(b, prefixLen, b.length);
    }

    /**
     * The {@code wire_format} (bytes 2..3) of a bare {@code MLSMessage}, or {@code -1} if it is not
     * one, including when the version (bytes 0..1) is not {@code 00 01}.
     */
    public static int wireFormatOf(final byte[] b) {
        if (b == null || b.length < 4) return -1;
        if ((b[0] & 0xFF) != 0x00 || (b[1] & 0xFF) != 0x01) return -1;
        return ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
    }

    /**
     * The {@code epoch} of a PublicMessage or PrivateMessage, which both start with
     * {@code group_id<V>} then a {@code u64 epoch}.
     *
     * @return the epoch, or {@code -1} for other wire formats or a truncated blob
     */
    public static long epochOf(final byte[] b) {
        final int wf = wireFormatOf(b);
        if (wf != 1 && wf != 2) return -1;        final int i = afterGroupId(b);
        if (i < 0 || i + 8 > b.length) return -1;
        long epoch = 0;
        for (int k = 0; k < 8; k++) {
            epoch = (epoch << 8) | (b[i + k] & 0xFF);
        }
        // A top-bit epoch would read as negative, i.e. absent; refuse it explicitly.
        return epoch < 0 ? -1 : epoch;
    }

    /**
     * Index of the first byte after {@code group_id<V>} (the start of the epoch), or {@code -1} if
     * truncated or using the reserved 8-byte prefix. Does not check the wire format.
     */
    private static int afterGroupId(final byte[] b) {
        int i = 4;  // past [version][wire_format]
        if (b == null || i >= b.length) return -1;
        final int first = b[i] & 0xFF;
        final int prefixLen = 1 << (first >>> 6);    // 00->1, 01->2, 10->4, 11->8
        if (prefixLen == 8) return -1;  // reserved by RFC 9420
        if (i + prefixLen > b.length) return -1;
        long gidLen = first & 0x3F;  // the top two bits select the prefix length
        for (int k = 1; k < prefixLen; k++) {
            gidLen = (gidLen << 8) | (b[i + k] & 0xFF);
        }
        i += prefixLen;
        if (gidLen < 0 || gidLen > b.length - i) return -1;
        return i + (int) gidLen;
    }

    /** Index just past an {@code opaque x<V>} starting at {@code i}, or {@code -1}. */
    private static int afterVector(final byte[] b, final int i) {
        if (b == null || i < 0 || i >= b.length) return -1;
        final int first = b[i] & 0xFF;
        final int prefixLen = 1 << (first >>> 6);
        if (prefixLen == 8) return -1;
        if (i + prefixLen > b.length) return -1;
        long len = first & 0x3F;
        for (int k = 1; k < prefixLen; k++) {
            len = (len << 8) | (b[i + k] & 0xFF);
        }
        final int after = i + prefixLen;
        if (len < 0 || len > b.length - after) return -1;
        return after + (int) len;
    }

    /** RFC 9420 SenderType. */
    public static final int SENDER_MEMBER = 1;
    public static final int SENDER_EXTERNAL = 2;
    public static final int SENDER_NEW_MEMBER_PROPOSAL = 3;
    public static final int SENDER_NEW_MEMBER_COMMIT = 4;

    /** Name of a SenderType, for logs. */
    public static String senderTypeName(final int t) {
        switch (t) {
            case SENDER_MEMBER: return "member";
            case SENDER_EXTERNAL: return "external";
            case SENDER_NEW_MEMBER_PROPOSAL: return "new_member_proposal";
            case SENDER_NEW_MEMBER_COMMIT: return "new_member_commit";
            default: return "unknown(" + t + ")";
        }
    }

    /**
     * The {@code sender_type} of a PublicMessage, or -1 (a PrivateMessage hides its sender).
     * Separates a {@code member} commit from a {@code new_member_commit}, which RFC 9420 §12.4.3.2
     * requires for ExternalInit.
     */
    public static int senderTypeOf(final byte[] b) {
        if (wireFormatOf(b) != WF_PUBLIC_MESSAGE) return -1;
        final int i = afterGroupId(b);
        if (i < 0 || i + 8 >= b.length) return -1;
        return b[i + 8] & 0xFF;  // past the epoch
    }

    /** RFC 9420 ContentType. */
    public static final int CONTENT_APPLICATION = 1;
    public static final int CONTENT_PROPOSAL = 2;
    public static final int CONTENT_COMMIT = 3;

    /** Name of a ContentType, for logs. */
    public static String contentTypeName(final int t) {
        switch (t) {
            case CONTENT_APPLICATION: return "application";
            case CONTENT_PROPOSAL: return "proposal";
            case CONTENT_COMMIT: return "commit";
            default: return "unknown(" + t + ")";
        }
    }

    /**
     * The {@code content_type} of a PublicMessage or PrivateMessage, or {@code -1} if unreadable or
     * outside 1 to 3; separates a commit from a proposal without decrypting. A PrivateMessage has
     * it in the clear right after the epoch; a PublicMessage has it after the Sender and
     * {@code authenticated_data<V>}.
     */
    public static int contentTypeOf(final byte[] b) {
        final int wf = wireFormatOf(b);
        if (wf != WF_PUBLIC_MESSAGE && wf != WF_PRIVATE_MESSAGE) return -1;
        final int afterGid = afterGroupId(b);
        if (afterGid < 0) return -1;
        int i = afterGid + 8;                        // past the u64 epoch
        if (i >= b.length) return -1;
        if (wf == WF_PRIVATE_MESSAGE) return named(b[i] & 0xFF);
        // PublicMessage: FramedContent = …epoch, Sender, authenticated_data<V>, content_type
        final int senderType = b[i] & 0xFF;
        i += 1;
        if (senderType == SENDER_MEMBER || senderType == SENDER_EXTERNAL) {
            i += 4;                                  // uint32 leaf_index / sender_index
        } else if (senderType != SENDER_NEW_MEMBER_PROPOSAL
                && senderType != SENDER_NEW_MEMBER_COMMIT) {
            return -1;                               // not a SenderType — the walk is off
        }
        i = afterVector(b, i);                       // authenticated_data<V>
        if (i < 0 || i >= b.length) return -1;
        return named(b[i] & 0xFF);
    }

    /** {@code t} if RFC 9420 defines it as a ContentType, else -1. */
    private static int named(final int t) {
        return (t == CONTENT_APPLICATION || t == CONTENT_PROPOSAL || t == CONTENT_COMMIT) ? t : -1;
    }

    public static byte[] findMlsMessage(final byte[] b, final int v0, final int v1, final int v2,
            final int v3) {
        if (b == null || b.length < 4) return null;
        // (1) At offset 0: a cleanly length-delimited artefact.
        if ((b[0] & 0xFF) == v0 && (b[1] & 0xFF) == v1 && (b[2] & 0xFF) == v2 && (b[3] & 0xFF)
                == v3) {
            return b;
        }
        // (2) Descend length-delimited protobuf fields for a cleanly wrapped message. Must precede
        // (3), which would take everything to the field end; malformed protobuf falls through to
        // (3).
        int i = 0;
        walk:
        while (i < b.length) {
            long tag = 0; int shift = 0; boolean ok = false;
            while (i < b.length && shift < 64) {
                final int c = b[i++] & 0xFF; tag |= (long) (c & 0x7f) << shift; shift += 7;
                if ((c & 0x80) == 0) { ok = true; break; }
            }
            if (!ok) break walk;
            final int wt = (int) (tag & 7);
            if (wt == 2) {                                  // length-delimited → recurse
                long len = 0; shift = 0; ok = false;
                while (i < b.length && shift < 64) {
                    final int c = b[i++] & 0xFF; len |= (long) (c & 0x7f) << shift; shift += 7;
                    if ((c & 0x80) == 0) { ok = true; break; }
                }
                if (!ok || len < 0 || i + len > b.length) break walk;
                final byte[] sub = java.util.Arrays.copyOfRange(b, i, i + (int) len);
                final byte[] hit = findMlsMessage(sub, v0, v1, v2, v3);
                if (hit != null) return hit;
                i += (int) len;
            } else if (wt == 0) {                           // varint
                while (i < b.length && (b[i++] & 0x80) != 0) { /* skip */ }
            } else if (wt == 5) { i += 4; }                 // fixed32
            else if (wt == 1) { i += 8; }                   // fixed64
            else { break walk; }                            // 3/4 (groups) / illegal → not protobuf
        }
        // (3) The marker may sit behind a short non-protobuf framing prefix (some peers' commits
        // start `4e XX 00 01 00 01`); return from it to the end of this field. Offset 0 was (1).
        final int scan = Math.min(FRAMING_PREFIX_MAX, b.length - 4);
        for (int off = 1; off <= scan; off++) {
            if ((b[off] & 0xFF) == v0 && (b[off + 1] & 0xFF) == v1
                    && (b[off + 2] & 0xFF) == v2 && (b[off + 3] & 0xFF) == v3) {
                return java.util.Arrays.copyOfRange(b, off, b.length);
            }
        }
        return null;
    }
}
