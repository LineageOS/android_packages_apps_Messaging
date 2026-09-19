/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * RCC.16 §7.6.3 {@code VerifiableDerivedContent}: the bytes an MLS-signed IMDN signs (RCC.16
 * §7.6.2, carried Base64 in the CPIM {@code MLS-Derived-Content-Signature} header). Enums are TLS
 * {@code uint16}; {@code opaque x<V>} uses the RFC 9420 §6.2.2 varint.
 */
public final class VerifiableDerivedContent {

    private VerifiableDerivedContent() { }

    /** RCC.16 §7.6.3.1 {@code VerifiableDerivedContentVersion}. */
    public static final int VERSION_RESERVED = 0;
    public static final int VERSION_V1 = 1;

    /** RCC.16 §7.6.3.2 {@code DeliveryNotificationStatus}. */
    public static final int DELIVERY_RESERVED = 0;
    public static final int DELIVERY_DELIVERED = 1;
    public static final int DELIVERY_FAILED = 2;
    public static final int DELIVERY_FORBIDDEN = 3;
    public static final int DELIVERY_ERROR = 4;

    /** RCC.16 §7.6.3.2 {@code MlsClientFailureReason}. */
    public static final int FAILURE_UNSET = 0;
    public static final int FAILURE_MESSAGE_FROM_NON_MEMBER = 1;
    public static final int FAILURE_INVALID_CREDENTIAL = 2;
    public static final int FAILURE_INVALID_COMMIT = 3;
    public static final int FAILURE_FAILURE_TO_DECRYPT = 4;
    public static final int FAILURE_COMMIT_IN_PRIVATEMESSAGE = 5;

    /** RCC.16 §7.6.3.3 {@code DisplayNotificationStatus}. */
    public static final int DISPLAY_RESERVED = 0;
    public static final int DISPLAY_DISPLAYED = 1;
    public static final int DISPLAY_FORBIDDEN = 2;
    public static final int DISPLAY_ERROR = 3;

    /**
     * RCC.16 §7.6.3.2 {@code VerifiableDeliveryImdn}, for positive and negative receipts alike.
     *
     * @param messageId the {@code <imdn><message-id>}, UTF-8
     * @param failureReason {@link #FAILURE_UNSET} for a delivered message
     */
    public static byte[] deliveryImdn(final int deliveryStatus, final String messageId,
            final int failureReason) {
        return deliveryImdn(deliveryStatus, messageId, failureReason, null, false);
    }

    /**
     * As above, with {@code insert} written verbatim before ({@code beforeReason}) or after the
     * failure reason, for probing a peer's decoder. A null {@code insert} gives the normal form.
     */
    public static byte[] deliveryImdn(final int deliveryStatus, final String messageId,
            final int failureReason, final byte[] insert, final boolean beforeReason) {
        return deliveryImdn(VERSION_V1, deliveryStatus, messageId, failureReason, insert,
                beforeReason);
    }

    /**
     * As above, with an explicit {@code version} for decoder probes; peers reject anything but 1.
     * {@code version < 0} means {@link #VERSION_V1}.
     */
    public static byte[] deliveryImdn(final int version, final int deliveryStatus,
            final String messageId, final int failureReason, final byte[] insert,
            final boolean beforeReason) {
        if (messageId == null) return null;
        return deliveryImdn(version, deliveryStatus,
                messageId.getBytes(StandardCharsets.UTF_8), failureReason, insert, beforeReason);
    }

    /** As above, with the reported message id as raw bytes rather than a UTF-8 string. */
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
                // Negative form only: a trailing u16 0 (an absent option) after the failure reason,
                // which peers' decoders require; a non-zero value makes them read further and fail.
                // Positive receipts end at the failure reason.
                u16(o, 0);
            }
            return o.toByteArray();
        } catch (final Throwable t) {
            return null;
        }
    }

    /** The type byte between the two parts of {@link #signedImdnContent}; not in the spec. */
    public static final int TYPE_DELIVERY = 1;
    public static final int TYPE_DISPLAY = 2;

    /**
     * The complete signed content of an RCC.16 §7.6 receipt, which is more than the inner struct:
     * <pre>
     *   u16     version = 1
     *   opaque  message_id&lt;V&gt;   the id of the receipt being sent, not of the reported message
     *   u32     era
     *   u8      type             {@link #TYPE_DELIVERY} or {@link #TYPE_DISPLAY}
     *   the VerifiableDeliveryImdn / VerifiableDisplayImdn struct
     *   u8      0x00             an absent RCC.16 §10.3 resent-message component
     * </pre>
     * The layout matches peers' receipts rather than a spec text.
     */
    public static byte[] signedImdnContent(final String ownMessageId, final int era,
            final int type, final byte[] inner) {
        return signedImdnContent(ownMessageId, era, type, inner, null);
    }

    /**
     * As above, with {@code trailing} (from {@link MlsResentMessage#encode}: tag {@code 0x02} and
     * the RCC.16 §11.3a HMAC field) in place of the absent {@code 0x00}; null gives the absent
     * form.
     */
    public static byte[] signedImdnContent(final String ownMessageId, final int era,
            final int type, final byte[] inner, final byte[] trailing) {
        return signedImdnContent(ownMessageId, era, /*epoch=*/ -1L, type, inner, trailing);
    }

    /**
     * As above, appending the epoch (u64) after the era when {@code epoch >= 0}. A decoder probe
     * only; the shipped form is era-only ({@code epoch < 0}).
     */
    public static byte[] signedImdnContent(final String ownMessageId, final int era,
            final long epoch, final int type, final byte[] inner, final byte[] trailing) {
        if (ownMessageId == null || inner == null) return null;
        try {
            final ByteArrayOutputStream o = new ByteArrayOutputStream(96);
            u16(o, VERSION_V1);
            opaque(o, ownMessageId.getBytes(StandardCharsets.UTF_8));
            o.write((era >>> 24) & 0xFF);
            o.write((era >>> 16) & 0xFF);
            o.write((era >>> 8) & 0xFF);
            o.write(era & 0xFF);
            if (epoch >= 0L) {  // epoch u64 big-endian after era u32
                for (int s = 56; s >= 0; s -= 8) o.write((int) ((epoch >>> s) & 0xFF));
            }
            o.write(type & 0xFF);
            o.write(inner, 0, inner.length);
            if (trailing == null || trailing.length == 0) {
                o.write(0x00);  // absent resent-message component
            } else {
                o.write(trailing, 0, trailing.length);  // present resent-message component
            }
            return o.toByteArray();
        } catch (final Throwable t) {
            return null;
        }
    }

    /** RCC.16 §7.6.3.3 {@code VerifiableDisplayImdn}; no failure-reason field. */
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

    /** RCC.16 §7.6.3.4 {@code VerifiableVideoChatMessage}, wrapping an opaque protobuf. */
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

    /** Parses a {@code VerifiableDeliveryImdn}; null on any malformation. */
    public static Delivery parseDelivery(final byte[] b) {
        try {
            final int[] p = {0};
            final int version = u16(b, p);
            final int status = u16(b, p);
            final byte[] mid = opaque(b, p);
            final int reason = u16(b, p);
            // Accept exactly one trailing u16 on the negative form (the absent option we and peers
            // send); its value is not surfaced.
            if (p[0] != b.length && !(reason != FAILURE_UNSET && p[0] + 2 == b.length)) {
                return null;  // trailing bytes: not this struct
            }
            return new Delivery(version, status, new String(mid, StandardCharsets.UTF_8), reason);
        } catch (final Throwable t) {
            return null;
        }
    }

    // ---- TLS primitives.

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

    /** Reads an RFC 9420 §6.2.2 varint-prefixed vector. */
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
