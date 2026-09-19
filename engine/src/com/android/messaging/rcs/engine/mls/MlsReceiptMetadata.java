/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.ArrayList;
import java.util.List;

/**
 * Outbound positive-delivery and display receipts: the receipt metadata protos and the MLS CPIM
 * header set (signature, then era; never Epoch-Authenticator or Original-Message-ID), merged into
 * the report's existing headers. Peers discard a receipt whose MLS headers are wrong, silently. The
 * signature comes from {@link VerifiableDerivedContent} (RCC.16 §7.6.3).
 */
public final class MlsReceiptMetadata {

    private MlsReceiptMetadata() {}

    /** The MLS CPIM header namespace — the same one {@link MlsHeaderGate} admits on. */
    public static final String NS = MlsHeaderGate.MLS_NAMESPACE;

    public static final String HDR_SIGNATURE = MlsHeaderGate.HDR_DERIVED_CONTENT_SIGNATURE;
    public static final String HDR_ERA_ID = MlsHeaderGate.HDR_ERA_ID;
    public static final String HDR_ORIGINAL_MESSAGE_ID = MlsHeaderGate.HDR_ORIGINAL_MESSAGE_ID;
    /** Present only so a test can assert it is never emitted on a receipt. */
    public static final String HDR_EPOCH_AUTHENTICATOR = MlsHeaderGate.HDR_EPOCH_AUTHENTICATOR;

    /** Receipt kinds by wire ordinal; {@link #DELIVERY_FAILED} uses the delivery handler. */
    public enum ReceiptType {
        /** Ordinal 0: the absence of a receipt kind; refused. */
        UNKNOWN_RECEIPT_TYPE(0),
        /** Ordinal 1, the delivery verb. */
        DELIVERY(1),
        /** Ordinal 2, the display verb. */
        DISPLAYED(2),
        /** Ordinal 3, routed to {@link #DELIVERY}'s handler. */
        DELIVERY_FAILED(3);

        public final int ordinalValue;

        ReceiptType(final int ordinalValue) { this.ordinalValue = ordinalValue; }

        /** Whether this kind is signed by the display verb rather than the delivery one. */
        public boolean usesDisplayVerb() { return this == DISPLAYED; }

        public static ReceiptType fromOrdinal(final int o) {
            for (final ReceiptType t : values()) {
                if (t.ordinalValue == o) return t;
            }
            return UNKNOWN_RECEIPT_TYPE;
        }
    }

    /**
     * Refuse the zero ordinal.
     *
     * @throws IllegalArgumentException for {@code null} or {@link ReceiptType#UNKNOWN_RECEIPT_TYPE}
     */
    public static void requireSignableReceiptType(final ReceiptType t) {
        if (t == null || t == ReceiptType.UNKNOWN_RECEIPT_TYPE) {
            throw new IllegalArgumentException("Unsupported receipt type: " + t);
        }
    }

    // ---- the metadata protos ------------------------------------------------------------------

    /** {@code DeliveryReceiptMetadata.status}: delivered. */
    public static final int STATUS_DELIVERED = 1;
    /** {@code DeliveryReceiptMetadata.status}: failed. */
    public static final int STATUS_FAILED = 2;
    /** Both metadata protos write {@code version = 1}. */
    public static final int VERSION = 1;

    /**
     * {@code DeliveryReceiptMetadata { 1 version, 2 status, 3 message_id, 6 original_message_id }}.
     * Fields 4 and 5 are a failure-reason oneof the outbound path never sets.
     */
    public static byte[] deliveryReceiptMetadata(final int status, final String messageId,
            final String originalMessageId) {
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        writeVarintField(out, 1, VERSION);
        writeVarintField(out, 2, status);
        writeBytesField(out, 3, messageId);
        if (includeOriginalMessageId(messageId, originalMessageId)) {
            writeBytesField(out, 6, originalMessageId);
        }
        return out.toByteArray();
    }

    /**
     * {@code DisplayReceiptMetadata { 1 version, 2 status, 3 message_id, 4 original_message_id }};
     * note field 4 here against 6 in the delivery metadata.
     */
    public static byte[] displayReceiptMetadata(final String messageId,
            final String originalMessageId) {
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        writeVarintField(out, 1, VERSION);
        writeVarintField(out, 2, STATUS_DELIVERED);   // display metadata always writes status 1
        writeBytesField(out, 3, messageId);
        if (includeOriginalMessageId(messageId, originalMessageId)) {
            writeBytesField(out, 4, originalMessageId);
        }
        return out.toByteArray();
    }

    /**
     * The metadata rule: {@code original_message_id} is set only when present and different from
     * the receipt's own message id.
     *
     * @see #includeOriginalMessageIdHeader
     */
    public static boolean includeOriginalMessageId(final String messageId,
            final String originalMessageId) {
        return originalMessageId != null
                && !originalMessageId.isEmpty()
                && !originalMessageId.equals(messageId);
    }

    /** The CPIM header rule, non-empty with no equality test; not used for receipts. */
    public static boolean includeOriginalMessageIdHeader(final String originalMessageId) {
        return originalMessageId != null && !originalMessageId.isEmpty();
    }

    // ---- the header set -----------------------------------------------------------------------

    /** One CPIM header: namespace, name, value. */
    public static final class Header {
        public final String namespace;
        public final String name;
        public final String value;

        public Header(final String namespace, final String name, final String value) {
            this.namespace = namespace;
            this.name = name;
            this.value = value;
        }

        @Override public boolean equals(final Object o) {
            if (!(o instanceof Header)) return false;
            final Header h = (Header) o;
            return namespace.equals(h.namespace) && name.equals(h.name) && value.equals(h.value);
        }

        @Override public int hashCode() {
            return (namespace + '\0' + name + '\0' + value).hashCode();
        }

        @Override public String toString() { return namespace + ' ' + name + '=' + value; }
    }

    /**
     * The receipt's MLS headers, in order: signature, then era. Empty when there is no signature,
     * since a peer refuses an unsigned MLS-tagged receipt. Epoch-Authenticator is never emitted on
     * a receipt, and Original-Message-ID is not either: the acknowledged id rides in the IMDN body
     * and peers treat a receipt carrying the header as failed.
     *
     * @param signatureB64 base64 of the signature blob; null/empty yields an empty list
     * @param eraId        the era, as a decimal string
     */
    public static List<Header> headers(final String signatureB64, final long eraId,
            final String originalMessageId) {
        final List<Header> out = new ArrayList<>(3);
        if (signatureB64 == null || signatureB64.isEmpty()) return out;
        out.add(new Header(NS, HDR_SIGNATURE, signatureB64));
        out.add(new Header(NS, HDR_ERA_ID, Long.toString(eraId)));
        return out;
    }

    /**
     * Merge the generated headers into a report's existing custom headers. Existing ones come
     * first; a generated header replaces an existing one with the same namespace and name.
     */
    public static List<Header> merge(final List<Header> existing, final List<Header> generated) {
        final List<Header> out = new ArrayList<>();
        if (existing != null) {
            for (final Header e : existing) {
                boolean replaced = false;
                if (generated != null) {
                    for (final Header g : generated) {
                        if (g.namespace.equals(e.namespace) && g.name.equals(e.name)) {
                            replaced = true;
                            break;
                        }
                    }
                }
                if (!replaced) out.add(e);
            }
        }
        if (generated != null) out.addAll(generated);
        return out;
    }

    // ---- minimal protobuf writers -------------------------------------------------------------

    private static void writeVarintField(final java.io.ByteArrayOutputStream out, final int field,
            final long value) {
        writeVarint(out, ((long) field << 3));       // wire type 0
        writeVarint(out, value);
    }

    private static void writeBytesField(final java.io.ByteArrayOutputStream out, final int field,
            final String utf8) {
        final byte[] b = utf8 == null ? new byte[0]
                : utf8.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeVarint(out, ((long) field << 3) | 2);   // wire type 2
        writeVarint(out, b.length);
        out.write(b, 0, b.length);
    }

    private static void writeVarint(final java.io.ByteArrayOutputStream out, final long v) {
        long x = v;
        while ((x & ~0x7FL) != 0) {
            out.write((int) ((x & 0x7F) | 0x80));
            x >>>= 7;
        }
        out.write((int) x);
    }
}
