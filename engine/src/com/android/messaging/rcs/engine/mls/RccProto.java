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
import java.util.ArrayList;
import java.util.List;

/**
 * The minimal protobuf codec the RCC.16 wire protos share.
 *
 * <p>Extracted from {@link RccFileInfo}, which had it private, when §7.10.4 {@code EpochIdentifier},
 * §7.10.5 {@code MlsEnhancedGroupInfo} and §7.13.4 {@code GroupMetadataKeys} arrived and would have
 * needed a second copy. Two copies of a codec is how the {@code fixed32}-versus-varint distinction
 * gets fixed in one place and not the other — and that particular bug is invisible below 128 bytes,
 * which is every test fixture anyone writes by hand.
 *
 * <p>Deliberately not a general protobuf library: no schema, no unknown-field retention, no groups.
 * It reads and writes exactly the shapes RCC.16 uses, and returns {@code null} rather than throwing
 * on malformation, because at every call site a proto we cannot parse is a proto we must not act on.
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

    /** A length-delimited field. A {@code null} value writes NOTHING — absent, not empty. */
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

    /** fixed32 — wire type 5, LITTLE-endian. Not a varint; the two agree only below 128. */
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

    /**
     * EVERY length-delimited value for {@code field}, in wire order — a {@code repeated} field.
     *
     * <p>{@link #field} returns only the first, which is right for a singular field and silently
     * wrong for a repeated one. §7.10.5's {@code committed_control_messages} is repeated and its
     * whole contract is "apply in sequential order", so dropping all but the first would look like
     * a working self-heal that never catches up.
     */
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
            // Return what we parsed. A truncated repeated field is still ordered up to the break.
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

    /** True iff {@code field} is present at all, whatever its wire type. Presence-as-flag. */
    static boolean hasField(final byte[] b, final int field) {
        if (b == null) return false;
        for (final int w : new int[] {WIRE_BYTES, WIRE_VARINT, WIRE_FIXED32, WIRE_FIXED64}) {
            if (scan(b, field, w) != null) return true;
        }
        return false;
    }

    /**
     * Walk the message for {@code field} of {@code wantWire}.
     * Returns {@code {payloadOffset, lengthOrValue}}, or {@code null}.
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
