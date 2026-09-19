/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.nio.charset.StandardCharsets;

/**
 * Application-message wire details sender and receiver must agree on: the
 * {@code AuthenticatedData} layout, era/epoch encoding and ordering, and the encrypt dispatch.
 * Pure apart from {@link #encryptDispatching}. See docs/mls/rcc16-map.md.
 */
public final class MlsAppMessage {

    private MlsAppMessage() {}

    /**
     * {@code [00 01][mls_varint len][message_id ASCII][uint32 era BE][00]}: the interoperable v3.0
     * layout, not RCC.16 v4.0's; change it only behind a negotiated v4.0 transport. Must agree with
     * the engine's {@code build_authenticated_data}, which builds outbound AAD.
     */
    public static byte[] buildAuthenticatedData(final String messageId, final int era) {
        return buildAuthenticatedData(messageId, era, null);
    }

    /** @param trailing the RCC.16 §10.3 resent-message slot, or null for the absent {@code 0x00} */
    public static byte[] buildAuthenticatedData(final String messageId, final int era,
            final byte[] trailing) {
        final byte[] mid = (messageId == null ? "" : messageId).getBytes(StandardCharsets.US_ASCII);
        final byte[] len = mlsVarint(mid.length);
        final byte[] tail = (trailing == null || trailing.length == 0)
                ? new byte[] { 0x00 } : trailing;
        final byte[] aad = new byte[2 + len.length + mid.length + 4 + tail.length];
        int i = 0;
        aad[i++] = 0x00; aad[i++] = 0x01;                 // version = 1
        System.arraycopy(len, 0, aad, i, len.length); i += len.length;
        System.arraycopy(mid, 0, aad, i, mid.length); i += mid.length;
        aad[i++] = (byte) (era >>> 24); aad[i++] = (byte) (era >>> 16);
        aad[i++] = (byte) (era >>> 8);  aad[i++] = (byte) era;
        System.arraycopy(tail, 0, aad, i, tail.length);   // trailing component
        return aad;
    }

    /** Null if the AAD does not parse; the trailing component is not read. */
    public static String aadMessageId(final byte[] aad) {
        if (aad == null || aad.length < 3) return null;
        if (aad[0] != 0x00 || aad[1] != 0x01) return null;          // version must be 1
        int i = 2;
        final int form = (aad[i] & 0xC0) >>> 6;
        final int nBytes = (form == 0) ? 1 : (form == 1) ? 2 : (form == 2) ? 4 : -1;
        if (nBytes < 0 || i + nBytes > aad.length) return null;
        int len = aad[i] & 0x3F;
        for (int k = 1; k < nBytes; k++) len = (len << 8) | (aad[i + k] & 0xFF);
        i += nBytes;
        if (len < 0 || i + len > aad.length) return null;
        return new String(aad, i, len, StandardCharsets.US_ASCII);
    }

    /** The resent-message slot ({@code 0x00} when absent), or null if the AAD does not parse. */
    public static byte[] aadTrailing(final byte[] aad) {
        if (aad == null || aad.length < 3) return null;
        if (aad[0] != 0x00 || aad[1] != 0x01) return null;
        int i = 2;
        final int form = (aad[i] & 0xC0) >>> 6;
        final int nBytes = (form == 0) ? 1 : (form == 1) ? 2 : (form == 2) ? 4 : -1;
        if (nBytes < 0 || i + nBytes > aad.length) return null;
        int len = aad[i] & 0x3F;
        for (int k = 1; k < nBytes; k++) len = (len << 8) | (aad[i + k] & 0xFF);
        i += nBytes;
        if (len < 0 || i + len > aad.length) return null;
        i += len;
        i += 4;                                        // the era u32
        if (i > aad.length) return null;
        final byte[] tail = new byte[aad.length - i];
        System.arraycopy(aad, i, tail, 0, tail.length);
        return tail;
    }

    /**
     * The trailing AAD byte is the RCC.16 §10.3 resent-message slot, not padding: an enum tag the
     * receiver tests against {@code 2}. The present form's tag value and prefix width are
     * unconfirmed.
     */
    public static final boolean TRAILING_IS_RESENT_COMPONENT = true;

    /** RFC 9420 §2.1.2 variable-length integer, the length prefix of an {@code opaque<V>}. */
    public static byte[] mlsVarint(final int value) {
        if (value < 0) throw new IllegalArgumentException("negative length: " + value);
        if (value < 0x40) {
            return new byte[] {(byte) value};
        }
        if (value < 0x4000) {
            return new byte[] {(byte) (0x40 | (value >>> 8)), (byte) value};
        }
        if (value < 0x40000000) {
            return new byte[] {(byte) (0x80 | (value >>> 24)), (byte) (value >>> 16),
                    (byte) (value >>> 8), (byte) value};
        }
        throw new IllegalArgumentException("length exceeds MLS varint range: " + value);
    }

    /** The era from the engine's 12-byte {@code eraEpoch} blob, or -1; never a guessed default. */
    public static int eraFrom(final byte[] eraEpoch) {
        if (eraEpoch == null || eraEpoch.length != 12) return -1;
        return ((eraEpoch[0] & 0xff) << 24) | ((eraEpoch[1] & 0xff) << 16)
                | ((eraEpoch[2] & 0xff) << 8) | (eraEpoch[3] & 0xff);
    }

    /** Returns -1 at the unsigned 32-bit ceiling: the era never wraps. */
    public static long nextEra(final long era) {
        if (era < 0L || era >= 0xFFFFFFFFL) return -1L;
        return era + 1L;
    }

    /**
     * The epoch from {@code [era u32 BE][epoch u64 BE]}, or -1. Unsigned: compare with
     * {@link Long#compareUnsigned} or use {@link Moment}.
     */
    public static long epochFrom(final byte[] eraEpoch) {
        if (eraEpoch == null || eraEpoch.length != 12) return -1L;
        long v = 0L;
        for (int i = 4; i < 12; i++) {
            v = (v << 8) | (eraEpoch[i] & 0xffL);
        }
        return v;
    }

    /**
     * A point in a conversation's MLS history, ordered era-major with both fields unsigned, so
     * {@code (1, 0)} is newer than {@code (0, 2^63)}.
     */
    public static final class Moment implements Comparable<Moment> {
        public final int era;
        public final long epoch;

        public Moment(final int era, final long epoch) {
            this.era = era;
            this.epoch = epoch;
        }

        /** Null if the blob is not 12 bytes. */
        public static Moment from(final byte[] eraEpoch) {
            if (eraEpoch == null || eraEpoch.length != 12) return null;
            return new Moment(eraFrom(eraEpoch), epochFrom(eraEpoch));
        }

        @Override
        public int compareTo(final Moment o) {
            final int e = Integer.compareUnsigned(era, o.era);
            return e != 0 ? e : Long.compareUnsigned(epoch, o.epoch);
        }

        public boolean isNewerThan(final Moment o) { return o != null && compareTo(o) > 0; }
        public boolean isOlderThan(final Moment o) { return o != null && compareTo(o) < 0; }

        @Override
        public boolean equals(final Object o) {
            if (!(o instanceof Moment)) return false;
            final Moment m = (Moment) o;
            return era == m.era && epoch == m.epoch;
        }

        @Override
        public int hashCode() {
            return era * 31 + (int) (epoch ^ (epoch >>> 32));
        }

        @Override
        public String toString() {
            return "(era=" + Integer.toUnsignedString(era)
                    + " epoch=" + Long.toUnsignedString(epoch) + ")";
        }
    }
}
