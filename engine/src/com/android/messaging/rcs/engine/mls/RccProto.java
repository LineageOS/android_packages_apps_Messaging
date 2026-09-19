/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The minimal protobuf codec shared by the RCC.16 wire messages: no schema, no unknown-field
 * retention, and {@code null} rather than an exception on malformed input.
 */
final class RccProto {

    private RccProto() { }

    static final int WIRE_VARINT = 0;
    static final int WIRE_FIXED64 = 1;
    static final int WIRE_BYTES = 2;
    static final int WIRE_FIXED32 = 5;

    // ---- encode ------------------------------------------------------------------------------

    static void rawVarint(final ByteArrayOutputStream o, long v) {
        while ((v & ~0x7FL) != 0) {
            o.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        o.write((int) v);
    }

    static void tag(final ByteArrayOutputStream o, final int field, final int wire) {
        rawVarint(o, ((long) field << 3) | wire);
    }

    /** A length-delimited field; a {@code null} value writes nothing (absent, not empty). */
    static void bytes(final ByteArrayOutputStream o, final int field, final byte[] v)
            throws java.io.IOException {
        if (v == null) return;
        tag(o, field, WIRE_BYTES);
        rawVarint(o, v.length);
        o.write(v);
    }

    static void varint(final ByteArrayOutputStream o, final int field, final long v) {
        tag(o, field, WIRE_VARINT);
        rawVarint(o, v);
    }

    /** Wire type 5, little-endian; not a varint, though the two agree below 128. */
    static void fixed32(final ByteArrayOutputStream o, final int field, final int v) {
        tag(o, field, WIRE_FIXED32);
        o.write(v & 0xFF);
        o.write((v >>> 8) & 0xFF);
        o.write((v >>> 16) & 0xFF);
        o.write((v >>> 24) & 0xFF);
    }

    // ---- decode ------------------------------------------------------------------------------

    /** First length-delimited value for {@code field}, or {@code null}. */
    static byte[] field(final byte[] b, final int field) {
        final int[] found = scan(b, field, WIRE_BYTES);
        if (found == null) return null;
        final byte[] out = new byte[found[1]];
        System.arraycopy(b, found[0], out, 0, found[1]);
        return out;
    }

    /** Every length-delimited value for a repeated {@code field}, in wire order. */
    static List<byte[]> repeatedField(final byte[] b, final int field) {
        final List<byte[]> out = new ArrayList<>();
        if (b == null) return out;
        int p = 0;
        try {
            while (p < b.length) {
                final long[] t = readVarint(b, p);
                p = (int) t[1];
                final int f = (int) (t[0] >>> 3);
                final int wire = (int) (t[0] & 7);
                switch (wire) {
                    case WIRE_VARINT: p = (int) readVarint(b, p)[1]; break;
                    case WIRE_FIXED64: p += 8; break;
                    case WIRE_BYTES: {
                        final long[] len = readVarint(b, p);
                        p = (int) len[1];
                        final int l = (int) len[0];
                        if (l < 0 || p + l > b.length) return out;
                        if (f == field) {
                            final byte[] v = new byte[l];
                            System.arraycopy(b, p, v, 0, l);
                            out.add(v);
                        }
                        p += l;
                        break;
                    }
                    case WIRE_FIXED32: p += 4; break;
                    default: return out;
                }
            }
        } catch (final Throwable t) {
            // A truncated message returns the entries parsed so far, still in order.
        }
        return out;
    }

    static long varintField(final byte[] b, final int field) {
        final int[] found = scan(b, field, WIRE_VARINT);
        if (found == null) return 0L;
        return readVarint(b, found[0])[0];
    }

    static int fixed32Field(final byte[] b, final int field) {
        final int[] found = scan(b, field, WIRE_FIXED32);
        if (found == null) return 0;
        final int p = found[0];
        return (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8)
                | ((b[p + 2] & 0xFF) << 16) | ((b[p + 3] & 0xFF) << 24);
    }

    /** True iff {@code field} is present with any wire type. */
    static boolean hasField(final byte[] b, final int field) {
        if (b == null) return false;
        for (final int w : new int[] {WIRE_BYTES, WIRE_VARINT, WIRE_FIXED32, WIRE_FIXED64}) {
            if (scan(b, field, w) != null) return true;
        }
        return false;
    }

    /**
     * Returns {@code {payloadOffset, lengthOrValue}} for {@code field} of {@code wantWire}, or
     * null.
     */
    static int[] scan(final byte[] b, final int field, final int wantWire) {
        if (b == null) return null;
        int p = 0;
        try {
            while (p < b.length) {
                final long[] t = readVarint(b, p);
                p = (int) t[1];
                final int f = (int) (t[0] >>> 3);
                final int wire = (int) (t[0] & 7);
                switch (wire) {
                    case WIRE_VARINT: {
                        final long[] v = readVarint(b, p);
                        if (f == field && wantWire == WIRE_VARINT) return new int[] {p, (int) v[0]};
                        p = (int) v[1];
                        break;
                    }
                    case WIRE_FIXED64:
                        if (f == field && wantWire == WIRE_FIXED64) return new int[] {p, 8};
                        p += 8;
                        break;
                    case WIRE_BYTES: {
                        final long[] len = readVarint(b, p);
                        p = (int) len[1];
                        final int l = (int) len[0];
                        if (l < 0 || p + l > b.length) return null;
                        if (f == field && wantWire == WIRE_BYTES) return new int[] {p, l};
                        p += l;
                        break;
                    }
                    case WIRE_FIXED32:
                        if (p + 4 > b.length) return null;
                        if (f == field && wantWire == WIRE_FIXED32) return new int[] {p, 4};
                        p += 4;
                        break;
                    default:
                        return null;
                }
            }
        } catch (final Throwable t) {
            return null;
        }
        return null;
    }

    /** Returns {@code {value, nextOffset}}. */
    static long[] readVarint(final byte[] b, int p) {
        long v = 0;
        int shift = 0;
        while (p < b.length) {
            final int c = b[p++] & 0xFF;
            v |= ((long) (c & 0x7F)) << shift;
            if ((c & 0x80) == 0) return new long[] {v, p};
            shift += 7;
            if (shift > 63) break;
        }
        return new long[] {v, p};
    }
}
