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
 * Locate a bare {@code MLSMessage} inside a larger blob.
 *
 * <p>Lives in the SHARED engine module, not in a transport: finding an MLS message by its
 * {@code [version][wire_format]} prefix is RFC-9420 framing, not backend framing, and both the
 * provider (Tachyon envelopes) and the app (join path) need it. It was
 * provider-private, which is part of why the app-side join path was never written — the tool it
 * needed was not reachable.
 *
 * <p>Wire formats: {@code 00 01 00 01} PublicMessage (Commit), {@code 00 01 00 02} PrivateMessage
 * (application), {@code 00 01 00 03} Welcome, {@code 00 01 00 04} GroupInfo.
 */
public final class MlsWireScan {

    private MlsWireScan() { }

    /** How far in the fallback prefix scan looks. A bare MlsMessage sits at most a few bytes behind a
     *  short non-protobuf framing prefix; scanning further risks false-matching inside certificate
     *  bytes. Kept at the provider's long-standing value. */
    private static final int FRAMING_PREFIX_MAX = 3;

    /** Welcome wire format — the join case. */
    public static byte[] findWelcome(final byte[] b) {
        return findMlsMessage(b, 0x00, 0x01, 0x00, 0x03);
    }

    // ---- RFC 9420 WireFormat, named ------------------------------------------------------------
    //
    // These were open-coded as `{0x00, 0x01, 0x00, 0x03}` byte quartets and as `b[3] >= 1 && <= 6`
    // in the provider's transport, which is how a wire-format test ends up being a thing a reader
    // has to decode rather than read. Named here because MlsServerBundle classifies a bundle BY
    // these values, and a classification built on magic numbers is one typo from silently calling
    // a Welcome a Commit.

    public static final int WF_PUBLIC_MESSAGE = 1;
    public static final int WF_PRIVATE_MESSAGE = 2;
    public static final int WF_WELCOME = 3;
    public static final int WF_GROUP_INFO = 4;
    public static final int WF_KEY_PACKAGE = 5;

    /**
     * The highest wire format treated as an MLSMessage. RFC 9420 defines 1-5; the provider's
     * long-standing test accepted 6 as well, and that tolerance is kept verbatim rather than
     * tightened here — narrowing it is a behaviour change to the inbound path, not a cleanup, and
     * it belongs to whoever can test it on a device.
     */
    private static final int WF_MAX_ACCEPTED = 6;

    /** Human name for a wire format, for the same 3am reason {@link #senderTypeName} exists. */
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

    /**
     * Does {@code b} begin with an RFC-9420 {@code MLSMessage} header?
     *
     * <p>The one test the bundle extraction runs on every candidate it pulls out of a relayed blob.
     * It is {@link #wireFormatOf} plus a range, and it is here rather than in a transport because
     * BOTH owners ask it — the provider of every length-delimited field it descends into, the app
     * of everything it is handed — and two copies of "is this an MLSMessage" is two chances to
     * disagree about what reaches an engine.
     */
    public static boolean isMlsMessage(final byte[] b) {
        final int wf = wireFormatOf(b);
        return wf >= WF_PUBLIC_MESSAGE && wf <= WF_MAX_ACCEPTED;
    }

    /**
     * Strip a leading RFC-9420 {@code mls_varint} length prefix — the inverse of
     * {@link MlsAppMessage#mlsVarint}.
     *
     * <p>STRICT ON PURPOSE: returns {@code null} unless the leading varint's value <i>exactly</i>
     * equals the remaining byte count. A bundle field holds either a bare MLSMessage or a
     * varint-wrapped one, and there is no way to tell by looking at the first byte — so the
     * extraction tries both. A lenient strip would succeed on the bare form too, hand a corrupted
     * MLSMessage to the engine, and draw a MALFORMED that reads like a peer bug.
     *
     * @return the wrapped body, or {@code null} if {@code b} is not varint-length-wrapped
     */
    public static byte[] stripMlsVarint(final byte[] b) {
        if (b == null || b.length == 0) return null;
        final int first = b[0] & 0xFF;
        final int prefixLen = 1 << (first >>> 6);   // top 2 bits: 00->1B, 01->2B, 10->4B, 11->8B
        if (b.length < prefixLen) return null;
        long v = first & 0x3F;
        for (int i = 1; i < prefixLen; i++) v = (v << 8) | (b[i] & 0xFF);
        if (v != (long) (b.length - prefixLen)) return null;
        return java.util.Arrays.copyOfRange(b, prefixLen, b.length);
    }

    /**
     * The {@code wire_format} of a bare {@code MLSMessage}, or {@code -1} if it is not one.
     *
     * <p>§10.8's "variant tag", and the first of the four store-time validations for a pending
     * message. An {@code MLSMessage} is {@code [u16 version][u16 wire_format]…}, so the tag is bytes
     * 2-3 big-endian: 1 PublicMessage, 2 PrivateMessage, 3 Welcome, 4 GroupInfo, 5 KeyPackage.
     *
     * <p>The version is checked too. A blob whose first two bytes are not {@code 00 01} is not an
     * MLSMessage at all, and returning its bytes 2-3 as a "wire format" would let arbitrary data
     * pass a validation whose entire job is to refuse it.
     */
    public static int wireFormatOf(final byte[] b) {
        if (b == null || b.length < 4) return -1;
        if ((b[0] & 0xFF) != 0x00 || (b[1] & 0xFF) != 0x01) return -1;
        return ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
    }

    /**
     * The {@code epoch} of a bare {@code MLSMessage}, or {@code -1} if it cannot be read.
     *
     * <p>Only PublicMessage(1) and PrivateMessage(2) carry one, and they carry it in the same place:
     * both begin with a {@code GroupID} (an opaque vector) followed by a {@code u64 epoch}. Welcome,
     * GroupInfo and KeyPackage have no epoch at this position, which is one of the reasons §10.8's
     * variant-tag check comes FIRST — the epoch read below is only meaningful once it has passed.
     *
     * <p>The opaque vector uses MLS's variable-length prefix, so its length header is 1, 2, 4 or 8
     * bytes depending on the top two bits of the first byte (RFC 9420 §2.1.2). Getting that wrong
     * reads the epoch from the middle of the group id — which would produce a plausible-looking
     * number rather than an error, so the decode is spelled out rather than assumed to be one byte.
     *
     * @return the epoch, or {@code -1} if the blob is not a group message or is truncated
     */
    public static long epochOf(final byte[] b) {
        final int wf = wireFormatOf(b);
        if (wf != 1 && wf != 2) return -1;          // no epoch at this position
        final int i = afterGroupId(b);
        if (i < 0 || i + 8 > b.length) return -1;
        long epoch = 0;
        for (int k = 0; k < 8; k++) {
            epoch = (epoch << 8) | (b[i + k] & 0xFF);
        }
        // An epoch with the top bit set would come back negative and be read as "absent" by every
        // caller. It is also not reachable in practice (epochs increment by one per commit), so
        // refusing is strictly better than silently returning a negative.
        return epoch < 0 ? -1 : epoch;
    }

    /**
     * The index just past the leading {@code opaque group_id<V>} of a PublicMessage/PrivateMessage —
     * i.e. the first byte of the {@code u64 epoch} — or {@code -1} if the blob is truncated or uses
     * the reserved 8-byte length prefix.
     *
     * <p>Extracted because {@link #epochOf}, {@link #senderTypeOf} and {@link #contentTypeOf} all
     * begin with exactly this walk, and three hand-copied length-prefix decoders is three chances to
     * get the {@code 1 << (first >>> 6)} selector wrong in one of them. Getting it wrong does not
     * throw — it reads a field from the middle of the group id and returns a plausible number.
     *
     * <p>Does NOT check the wire format: callers do, because which formats are legal differs per
     * caller (PrivateMessage has an epoch and a content type but no Sender).
     */
    private static int afterGroupId(final byte[] b) {
        int i = 4;                                   // past [version][wire_format]
        if (b == null || i >= b.length) return -1;
        final int first = b[i] & 0xFF;
        final int prefixLen = 1 << (first >>> 6);    // 00->1, 01->2, 10->4, 11->8
        if (prefixLen == 8) return -1;               // reserved by RFC 9420; refuse rather than guess
        if (i + prefixLen > b.length) return -1;
        long gidLen = first & 0x3F;                  // the top two bits are the prefix selector
        for (int k = 1; k < prefixLen; k++) {
            gidLen = (gidLen << 8) | (b[i + k] & 0xFF);
        }
        i += prefixLen;
        if (gidLen < 0 || gidLen > b.length - i) return -1;
        return i + (int) gidLen;
    }

    /**
     * The index just past an {@code opaque x<V>} that starts at {@code i}, or {@code -1}.
     *
     * <p>Same RFC 9420 §2.1.2 prefix as {@link #afterGroupId}, but for a vector at an arbitrary
     * offset — {@code FramedContent.authenticated_data}, which sits between the Sender and the
     * content type.
     */
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

    /** Human name for a SenderType, for logs that have to be readable at 3am. */
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
     * The {@code FramedContent.sender.sender_type} of a PublicMessage/PrivateMessage, or -1.
     *
     * <p>Worth having as a first-class read because it is the difference between two MLS validation
     * failures that look identical from the outside. An ExternalInit proposal is only valid from
     * {@code new_member_commit} (RFC 9420 §12.4.3.2) — including the RESYNC case, where you rejoin as
     * a new member and remove your old leaf in the same commit. Send that same commit framed as
     * {@code member} and the answer is "Invalid proposal type for sender", which reads exactly like a
     * server policy refusal but is ordinary sender validation doing its job.
     *
     * <p>Layout is the same walk {@link #epochOf} does, then the 8-byte epoch, then this byte.
     *
     * <p><b>PublicMessage ONLY, and this used to accept PrivateMessage too.</b> A PrivateMessage has
     * no {@code Sender} at all — RFC 9420 §6.3 is {@code {group_id, epoch, content_type,
     * authenticated_data, encrypted_sender_data, ciphertext}}, the sender being the thing it hides —
     * so the byte this returned for a wf=2 blob was its {@code content_type}. It never misled anyone
     * because the one caller passes a Commit, which our engine frames as a PublicMessage
     * ({@code encrypt_control_messages} is false). Corrected here because {@link #contentTypeOf}
     * reads that very byte one method below, and two functions disagreeing about what lives at one
     * offset is how the next reader loses an afternoon.
     */
    public static int senderTypeOf(final byte[] b) {
        if (wireFormatOf(b) != WF_PUBLIC_MESSAGE) return -1;
        final int i = afterGroupId(b);
        if (i < 0 || i + 8 >= b.length) return -1;
        return b[i + 8] & 0xFF;                      // past the epoch
    }

    /** RFC 9420 ContentType. */
    public static final int CONTENT_APPLICATION = 1;
    public static final int CONTENT_PROPOSAL = 2;
    public static final int CONTENT_COMMIT = 3;

    /** Human name for a ContentType, for the same reason {@link #senderTypeName} exists. */
    public static String contentTypeName(final int t) {
        switch (t) {
            case CONTENT_APPLICATION: return "application";
            case CONTENT_PROPOSAL: return "proposal";
            case CONTENT_COMMIT: return "commit";
            default: return "unknown(" + t + ")";
        }
    }

    /**
     * The {@code content_type} of a PublicMessage/PrivateMessage — {@link #CONTENT_APPLICATION},
     * {@link #CONTENT_PROPOSAL} or {@link #CONTENT_COMMIT} — or {@code -1} if it cannot be read.
     *
     * <p><b>This is the read that separates a commit from a proposal without decrypting anything</b>,
     * which is what {@link MlsInboundHold} needs: holding a proposal while letting its commit through
     * produces a commit that references a proposal we never saw, i.e. an apply failure rather than the
     * clean epoch gap the fixture is for.
     *
     * <p>The two wire formats put it in different places, and that is the whole of this method:
     * <ul>
     *   <li><b>PrivateMessage (2)</b> — {@code {group_id<V>, epoch, content_type, …}}: the byte
     *       straight after the epoch. It is in the CLEAR even though the sender is not, because the
     *       receiver has to know which key schedule to use before it can decrypt.</li>
     *   <li><b>PublicMessage (1)</b> — the content type sits inside {@code FramedContent}, after the
     *       {@code Sender} (1 byte of type plus a {@code uint32} leaf/sender index for
     *       {@code member}/{@code external}, and nothing at all for the two {@code new_member_*}
     *       forms) and after {@code authenticated_data<V>}.</li>
     * </ul>
     *
     * <p>Returns {@code -1} rather than the raw byte for anything outside 1-3. A walk that lands on
     * a value RFC 9420 does not define has almost certainly landed in the wrong place, and "we could
     * not read a content type" is a true statement where "the content type is 47" is not.
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

    /** {@code t} if RFC 9420 defines it as a ContentType, else -1. See {@link #contentTypeOf}. */
    private static int named(final int t) {
        return (t == CONTENT_APPLICATION || t == CONTENT_PROPOSAL || t == CONTENT_COMMIT) ? t : -1;
    }

    public static byte[] findMlsMessage(final byte[] b, final int v0, final int v1, final int v2, final int v3) {
        if (b == null || b.length < 4) return null;
        // (1) Exact start — a cleanly length-delimited artifact (e.g. the Welcome, wrapped in a
        // `12 <len>` protobuf field whose content begins with `00 01 00 03`).
        if ((b[0] & 0xFF) == v0 && (b[1] & 0xFF) == v1 && (b[2] & 0xFF) == v2 && (b[3] & 0xFF) == v3) {
            return b;
        }
        // (2) Protobuf recursion — descend length-delimited fields to find a cleanly-wrapped bare
        // MlsMessage. This MUST run before the prefix scan (3): the Welcome sits in a proper
        // `12 <len>` field, so recursion returns exactly its bytes; a premature prefix scan here
        // would instead grab everything from the marker to the field end (trailing bytes → decode
        // error). On malformed protobuf we `break` to (3) rather than abort — the Commit's leaf is
        // NOT valid protobuf (see below), so the walk stops there and the scan takes over.
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
        // (3) Fallback prefix scan — the marker may sit a few bytes in behind a SHORT non-protobuf
        // framing prefix. Google Messages kind=47 Commits wrap the artifact as `4e XX 00 01 00 01 …`
        // inside the innermost length field; `4e XX` is not a valid protobuf tag, so (2) stops on it
        // and lands here. Return from the marker to the end of THIS field (its exact boundary).
        // Bounded to FRAMING_PREFIX_MAX so a coincidental {00,01,00,0X} deep inside a DER cert
        // (certs start `30 82 …`, never at a small field offset) can never false-match. Start at
        // off=1 — off=0 was handled by (1). (device-observed: kind=47 Commit from a peer
        // → UnsupportedEnumDiscriminant before this fallback.)
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
