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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * RCC.16 <b>§7.6.3</b> — {@code VerifiableDerivedContent}, the signed payload behind an MLS-signed IMDN.
 *
 * <p>§7.6.2 uses these bytes as the {@code authenticated_data} of a FramedContent, which becomes an MLS
 * PublicMessage typed {@code rcs_signature} (0xF002), Base64'd into the CPIM
 * {@code MLS-Derived-Content-Signature} header. So this struct is <em>what actually gets signed</em>:
 * a receipt whose derived content is wrong is a signature over the wrong statement, and the peer's
 * validation fails with nothing to point at.
 *
 * <p>All enums are TLS {@code uint16} — every one is declared with a {@code (65535)} upper bound in the
 * spec — and {@code opaque x<V>} is an MLS variable-length vector, i.e. the RFC 9420 §6.2.2 varint
 * length prefix then the bytes. The varint comes from {@link MlsAppMessage} rather than a second copy.
 */
public final class VerifiableDerivedContent {

    private VerifiableDerivedContent() { }

    /** §7.6.3.1 {@code VerifiableDerivedContentVersion}. */
    public static final int VERSION_RESERVED = 0;
    public static final int VERSION_V1 = 1;

    /** §7.6.3.2 {@code DeliveryNotificationStatus}. */
    public static final int DELIVERY_RESERVED = 0;
    public static final int DELIVERY_DELIVERED = 1;
    public static final int DELIVERY_FAILED = 2;
    public static final int DELIVERY_FORBIDDEN = 3;
    public static final int DELIVERY_ERROR = 4;

    /** §7.6.3.2 {@code MlsClientFailureReason}. */
    public static final int FAILURE_UNSET = 0;
    public static final int FAILURE_MESSAGE_FROM_NON_MEMBER = 1;
    public static final int FAILURE_INVALID_CREDENTIAL = 2;
    public static final int FAILURE_INVALID_COMMIT = 3;
    public static final int FAILURE_FAILURE_TO_DECRYPT = 4;
    public static final int FAILURE_COMMIT_IN_PRIVATEMESSAGE = 5;

    /** §7.6.3.3 {@code DisplayNotificationStatus}. */
    public static final int DISPLAY_RESERVED = 0;
    public static final int DISPLAY_DISPLAYED = 1;
    public static final int DISPLAY_FORBIDDEN = 2;
    public static final int DISPLAY_ERROR = 3;

    /**
     * §7.6.3.2 {@code VerifiableDeliveryImdn} — used for BOTH positive and negative delivery IMDNs
     * ("structured in the same way", so the status field is what distinguishes them, not the struct).
     *
     * @param messageId the {@code <imdn><message-id>} element, UTF-8
     * @param failureReason {@link #FAILURE_UNSET} for a delivered message
     */
    public static byte[] deliveryImdn(final int deliveryStatus, final String messageId,
            final int failureReason) {
        return deliveryImdn(deliveryStatus, messageId, failureReason, null, false);
    }

    /**
     * As above, but with EXPERIMENTAL RAW bytes inserted in the inner for on-device bisection.
     *
     * <p><b>Why.</b> Google Messages' type-1 DELIVERY inner decoder
     * reads MORE than the single {@code opaque<V>} (reported_id) our §7.6.3.2 model
     * emits — read order {@code version u16 | status | reported_id opaque | <ENUM> | opaque | …} — so a
     * reason-4 receipt (the only one routed to this decoder; positives verify by an earlier path)
     * {@code UnexpectedEOF}s on the missing tail → {@code UNABLE_TO_DESERIALIZE_CUSTOM_PAYLOAD(51)},
     * no resend. Exact offsets/widths are NOT statically recoverable (call-based codec readers), so
     * the CallFlogger error TYPE localises it on-device: {@code UnexpectedEOF} = still short here;
     * {@code UnsupportedEnumDiscriminant} = hit the enum, sweep the byte (incl {@code 0x00});
     * moves/clears = advance. {@code insert} is written VERBATIM (caller controls every byte — a bare
     * {@code 00}, a discriminant, a discriminant + a hand-built {@code opaque<V>}); {@code beforeReason}
     * places it between reported_id and failure_reason, else after. null → the one-opaque form.
     */
    public static byte[] deliveryImdn(final int deliveryStatus, final String messageId,
            final int failureReason, final byte[] insert, final boolean beforeReason) {
        return deliveryImdn(VERSION_V1, deliveryStatus, messageId, failureReason, insert, beforeReason);
    }

    /**
     * As above, with an overridable {@code version} — <b>a DECODER lever, not a reason-9 sweep.</b>
     *
     * <p><b>RETRACTED.</b> This javadoc used to say Google Messages
     * compares OUR receipt's version and status
     * against its recompute, so a value clearing reason-9 would be its expected value. That was
     * withdrawn: <b>the peer never constructs that struct</b> — Google Messages
     * parses our IMDN XML and builds it itself, writing the version and status as
     * host constants on a straight unguarded line. With the comparator's direction the other way
     * round, gates 3 and 4 read exactly those two
     * constants and are peer-unfailable. Reason 9 itself is closed as ARCHITECTURAL:
     * gate 1 reprocesses the FTD's own content keyed on the receipt's own id,
     * and nothing in the structures it reads aims it at the referenced message.
     *
     * <p>So sweeping this CANNOT move what reason 9 compares, and a reason-9 null from it is expected
     * and uninformative. What it does measure is Google Messages' DECODER: any version ≠ 1 fails with
     * {@code UNABLE_TO_DESERIALIZE_CUSTOM_PAYLOAD(51)}, which is why 1 is forced.
     * {@code version < 0} keeps {@link #VERSION_V1}.
     */
    public static byte[] deliveryImdn(final int version, final int deliveryStatus,
            final String messageId, final int failureReason, final byte[] insert,
            final boolean beforeReason) {
        if (messageId == null) return null;
        return deliveryImdn(version, deliveryStatus,
                messageId.getBytes(StandardCharsets.UTF_8), failureReason, insert, beforeReason);
    }

    /**
     * As above, with the reported message-id as RAW bytes (reason-9 GATE1 msgid-form).
     * Google Messages' GATE1 compares OUR reported_id byte-for-byte
     * against its recompute's msgid form; if Google Messages' form is not the UTF-8 string we emit (e.g.
     * base64-decoded bytes, or a prefix-stripped id), we must match its exact bytes. Lets a sweep
     * set any reported_id form independently of the Original-Message-ID lookup id.
     */
    public static byte[] deliveryImdn(final int version, final int deliveryStatus,
            final byte[] reportedId, final int failureReason, final byte[] insert,
            final boolean beforeReason) {
        if (reportedId == null) return null;
        try {
            final boolean hasInsert = insert != null && insert.length > 0;
            final ByteArrayOutputStream o = new ByteArrayOutputStream(48);
            u16(o, version < 0 ? VERSION_V1 : version);
            u16(o, deliveryStatus);
            opaque(o, reportedId);
            if (hasInsert && beforeReason) o.write(insert);
            u16(o, failureReason);
            if (hasInsert && !beforeReason) {
                o.write(insert);
            } else if (!hasInsert && failureReason != FAILURE_UNSET) {
                // FIELD #4 — MANDATORY ON THE NEGATIVE FORM, confirmed on a device.
                //
                // Google Messages' type-1 DELIVERY inner decoder reads a further field after failure_reason
                // that §7.6.3.2 as we modelled it did not emit, so every reason-4 receipt died
                // UnexpectedEOF -> UNABLE_TO_DESERIALIZE_CUSTOM_PAYLOAD(51) and Google Messages' resend-prep
                // never ran. Supplying it makes the decode COMPLETE (device-proven against two
                // shipping Google Messages builds). It is an OPTION discriminant: 0x0000 = None is correct
                // and is what Google Messages accepts; a NON-zero value is Some and makes Google Messages read on for
                // a payload we do not carry, reproducing the EOF (swept: 0001/0002/0004/0006 all -> 51).
                //
                // NEGATIVE ARM ONLY. Google Messages' own measured POSITIVE sample ends after failure_reason
                // with no such field (68B: 0001 18 <own> 00000005 01 0001 0001 1c <reported> 0000 00),
                // and positives verify by an earlier path that never reaches this decoder — so
                // emitting it on a positive would be a divergence with no evidence behind it.
                u16(o, 0);
            }
            return o.toByteArray();
        } catch (final Throwable t) {
            return null;
        }
    }

    /** §7.6.3.3 {@code VerifiableDisplayImdn}. Note: no failure-reason field, unlike delivery. */
    /** The 1-byte discriminator Google Messages writes between the two structures. Measured, not spec'd. */
    public static final int TYPE_DELIVERY = 1;
    public static final int TYPE_DISPLAY = 2;

    /**
     * The COMPLETE signed content for a §7.6 receipt — what Google Messages actually signs, which is more
     * than the {@code VerifiableXxxImdn} struct alone.
     *
     * <p><b>Why this exists.</b> Our receipts were REJECTED by Google Messages
     * ({@code processAndValidateReceiptSync -> FAILED_MESSAGE / FAIL_NO_RETRY})
     * while our messages interoperated fine in both directions. We signed the inner struct only;
     * Google Messages signs a prefix as well, so it reconstructed different bytes and the signature verified
     * against nothing.
     *
     * <h2>The layout, measured from five captured Google Messages receipts (two devices)</h2>
     *
     * <pre>
     *   version u16 = 1
     *   opaque  message_id&lt;V&gt;      the SIGNER'S OWN receipt id (NOT the one being reported on)
     *   uint32  era
     *   uint8   type                1 = delivery, 2 = display
     *   &lt;the VerifiableDeliveryImdn / VerifiableDisplayImdn struct, unchanged&gt;
     *   uint8   0x00                trailing, present on BOTH types
     * </pre>
     *
     * <p>Two Google Messages samples, differing only where the layout says they should:
     *
     * <pre>
     *   68B  0001 18 &lt;24B own id&gt; 00000005  01  0001 0001 1c &lt;28B reported id&gt; 0000  00
     *   66B  0001 18 &lt;24B own id&gt; 00000005  02  0001 0001 1c &lt;28B reported id&gt;       00
     * </pre>
     *
     * The delivery form carries the extra {@code failureReason} u16 and the display form does not,
     * which is exactly §7.6.3.2 vs §7.6.3.3 — that agreement across the varying field is what makes
     * the split trustworthy rather than a shape match. Our own emission was byte-identical to the
     * INNER part, which is why everything except the final verify worked.
     *
     * <p><b>The two ids are different and their order matters.</b> The outer one is the id of the
     * receipt we are sending; the inner one is the id of the message being reported on. Swapping
     * them reproduces the bug with different bytes.
     *
     * <p><b>The trailing {@code 0x00} is measured, not explained.</b> It is present on both types in
     * every sample. It may be an empty {@code opaque<V>} (padding or an extension slot). Recorded as
     * observed rather than named, because guessing its identity would not change the bytes.
     */
    public static byte[] signedImdnContent(final String ownMessageId, final int era,
            final int type, final byte[] inner) {
        return signedImdnContent(ownMessageId, era, type, inner, null);
    }

    /**
     * As above, but with an EXPLICIT trailing §10.3 resent-message component instead of the absent
     * {@code 0x00}.
     *
     * <p><b>Why this overload exists.</b> Google Messages' negative-receipt handler shows the
     * {@code UNABLE_TO_DESERIALIZE_CUSTOM_PAYLOAD(51)} that blocks Google Messages' resend-prep is an
     * {@code MlsCodecError_UnexpectedEOF}: on a reason-4 receipt Google Messages reads PAST our derived
     * content for a §10.3 resent-message component that our trailing bare {@code 0x00} declares
     * ABSENT. The empty {@code 0x00} is a valid empty opaque and parses fine for POSITIVES (they
     * never reach this parse), so the fix is to SUPPLY the present-form component here, not to widen
     * the trailing. {@code trailing} is {@link MlsResentMessage#encode} output (tag {@code 0x02} ||
     * length-prefixed opaque carrying the §11.3a HMAC field); pass null for the ordinary absent form.
     */
    public static byte[] signedImdnContent(final String ownMessageId, final int era,
            final int type, final byte[] inner, final byte[] trailing) {
        return signedImdnContent(ownMessageId, era, /*epoch=*/ -1L, type, inner, trailing);
    }

    /**
     * As above, with the FULL MOMENT (era u32 || epoch u64) when {@code epoch >= 0}.
     *
     * <p><b>RETRACTED 2026-09-12 — this tests a field that does not exist.</b> The
     * javadoc used to say Google Messages requires the receipt's {era, epoch, moment} to
     * equal its recompute. That was killed statically: the struct has no era,
     * epoch or moment member at all — era rides in a different buffer — and our
     * own device run killed it empirically (peer and Google Messages both at era 6 / epoch 2, reason 9 still
     * fired). A reason-9 null from this lever is EXPECTED and is not evidence.
     *
     * <p>Kept as a gated DECODER probe only: a decode error would show Google Messages will not accept an epoch
     * after the era here. {@code epoch < 0} keeps the measured era-only form, which is what we ship.
     */
    public static byte[] signedImdnContent(final String ownMessageId, final int era, final long epoch,
            final int type, final byte[] inner, final byte[] trailing) {
        if (ownMessageId == null || inner == null) return null;
        try {
            final ByteArrayOutputStream o = new ByteArrayOutputStream(96);
            u16(o, VERSION_V1);
            opaque(o, ownMessageId.getBytes(StandardCharsets.UTF_8));
            o.write((era >>> 24) & 0xFF);
            o.write((era >>> 16) & 0xFF);
            o.write((era >>> 8) & 0xFF);
            o.write(era & 0xFF);
            if (epoch >= 0L) {                       // full moment: append epoch u64 BE after era u32
                for (int s = 56; s >= 0; s -= 8) o.write((int) ((epoch >>> s) & 0xFF));
            }
            o.write(type & 0xFF);
            o.write(inner, 0, inner.length);
            if (trailing == null || trailing.length == 0) {
                o.write(0x00);                       // absent §10.3 component (measured default)
            } else {
                o.write(trailing, 0, trailing.length);   // present-form §10.3 resent-message component
            }
            return o.toByteArray();
        } catch (final Throwable t) {
            return null;
        }
    }

    public static byte[] displayImdn(final int displayStatus, final String messageId) {
        if (messageId == null) return null;
        try {
            final ByteArrayOutputStream o = new ByteArrayOutputStream(32);
            u16(o, VERSION_V1);
            u16(o, displayStatus);
            opaque(o, messageId.getBytes(StandardCharsets.UTF_8));
            return o.toByteArray();
        } catch (final Throwable t) {
            return null;
        }
    }

    /** §7.6.3.4 {@code VerifiableVideoChatMessage} — the VideoChatMessage protobuf, opaque to us. */
    public static byte[] videoChat(final byte[] videoChatMessageProto) {
        if (videoChatMessageProto == null) return null;
        try {
            final ByteArrayOutputStream o = new ByteArrayOutputStream(
                    videoChatMessageProto.length + 8);
            u16(o, VERSION_V1);
            opaque(o, videoChatMessageProto);
            return o.toByteArray();
        } catch (final Throwable t) {
            return null;
        }
    }

    /** Parsed view of a received delivery IMDN's derived content. */
    public static final class Delivery {
        public final int version;
        public final int status;
        public final String messageId;
        public final int failureReason;
        Delivery(int v, int s, String m, int f) {
            version = v; status = s; messageId = m; failureReason = f;
        }
    }

    /**
     * Parse a {@code VerifiableDeliveryImdn}. Returns null on any malformation — a receipt whose
     * derived content will not parse must not be treated as a valid statement about a message.
     */
    public static Delivery parseDelivery(final byte[] b) {
        try {
            final int[] p = {0};
            final int version = u16(b, p);
            final int status = u16(b, p);
            final byte[] mid = opaque(b, p);
            final int reason = u16(b, p);
            // FIELD #4 — accept the negative form's trailing Option. We now EMIT it on
            // every negative receipt, so a strict "must consume exactly" would make us reject our own
            // (and Google Messages') negative receipts. Exactly one trailing u16 is allowed, and only on the
            // negative form; anything else still fails, so this stays a shape check rather than a
            // shrug. The value is not surfaced: 0=None is the only form either side emits, and a
            // Some we cannot decode must not be reported as if we had understood it.
            if (p[0] != b.length && !(reason != FAILURE_UNSET && p[0] + 2 == b.length)) {
                return null;                        // trailing bytes = not this struct
            }
            return new Delivery(version, status, new String(mid, StandardCharsets.UTF_8), reason);
        } catch (final Throwable t) {
            return null;
        }
    }

    // ---- TLS primitives ----

    private static void u16(final ByteArrayOutputStream o, final int v) {
        o.write((v >>> 8) & 0xFF);
        o.write(v & 0xFF);
    }

    private static void opaque(final ByteArrayOutputStream o, final byte[] v)
            throws java.io.IOException {
        o.write(MlsAppMessage.mlsVarint(v.length));
        o.write(v);
    }

    private static int u16(final byte[] b, final int[] p) {
        final int v = ((b[p[0]] & 0xFF) << 8) | (b[p[0] + 1] & 0xFF);
        p[0] += 2;
        return v;
    }

    /** Read an MLS varint-prefixed vector (RFC 9420 §6.2.2: the top two bits give the width). */
    private static byte[] opaque(final byte[] b, final int[] p) {
        final int first = b[p[0]] & 0xFF;
        final int prefix = first >>> 6;
        final int len;
        if (prefix == 0) {
            len = first & 0x3F;
            p[0] += 1;
        } else if (prefix == 1) {
            len = ((first & 0x3F) << 8) | (b[p[0] + 1] & 0xFF);
            p[0] += 2;
        } else if (prefix == 2) {
            len = ((first & 0x3F) << 24) | ((b[p[0] + 1] & 0xFF) << 16)
                    | ((b[p[0] + 2] & 0xFF) << 8) | (b[p[0] + 3] & 0xFF);
            p[0] += 4;
        } else {
            throw new IllegalArgumentException("reserved varint prefix");
        }
        final byte[] out = new byte[len];
        System.arraycopy(b, p[0], out, 0, len);
        p[0] += len;
        return out;
    }
}
