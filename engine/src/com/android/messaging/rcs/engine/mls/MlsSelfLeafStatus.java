/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * Our own leaf in one MLS group compared with the certificate this client holds, from which the
 * certificate-driven Self-Update is decided. mls-rs reuses a leaf's credential until a new signing
 * identity is supplied, and the server validates the roster's copy (RCC.16 A.4.3.1), so the
 * group's copy is what matters. {@link #stale} compares certificates, not keys: a renewal keeps the
 * key. Unparseable windows read as 0. See docs/mls/credentials.md.
 */
public final class MlsSelfLeafStatus {

    /** Fixed width of the engine's record: {@code u32 + 4×u64 + u8}. */
    public static final int WIRE_BYTES = 37;

    /** Our leaf index in the group's ratchet tree. */
    public final int leafIndex;
    /** {@code notBefore} of the group's certificate for us, epoch seconds; 0 if unreadable. */
    public final long groupNotBefore;
    /** {@code notAfter} of the group's certificate for us, epoch seconds; 0 if unreadable. */
    public final long groupNotAfter;
    /** {@code notBefore} of this client's certificate, epoch seconds; 0 if unreadable. */
    public final long clientNotBefore;
    /** {@code notAfter} of this client's certificate, epoch seconds; 0 if unreadable. */
    public final long clientNotAfter;
    /** Whether the two certificates differ, so a Self-Update would carry a new credential. */
    public final boolean stale;

    public MlsSelfLeafStatus(final int leafIndex, final long groupNotBefore,
            final long groupNotAfter, final long clientNotBefore, final long clientNotAfter,
            final boolean stale) {
        this.leafIndex = leafIndex;
        this.groupNotBefore = groupNotBefore;
        this.groupNotAfter = groupNotAfter;
        this.clientNotBefore = clientNotBefore;
        this.clientNotAfter = clientNotAfter;
        this.stale = stale;
    }

    /**
     * Decodes the engine's fixed-width record, or null if absent or the wrong length; a short
     * record is never partially filled.
     */
    public static MlsSelfLeafStatus parse(final byte[] r) {
        if (r == null || r.length != WIRE_BYTES) return null;
        int idx = 0;
        for (int i = 0; i < 4; i++) idx = (idx << 8) | (r[i] & 0xFF);
        return new MlsSelfLeafStatus(idx, be64(r, 4), be64(r, 12), be64(r, 20), be64(r, 28),
                r[36] != 0);
    }

    private static long be64(final byte[] r, final int off) {
        long v = 0;
        for (int i = off; i < off + 8; i++) v = (v << 8) | (r[i] & 0xFFL);
        return v;
    }

    /**
     * Whole days left on the group's copy at {@code nowSecs}; negative once lapsed,
     * {@link Long#MIN_VALUE} when unreadable.
     */
    public long groupRemainingDays(final long nowSecs) {
        if (groupNotAfter <= 0L) return Long.MIN_VALUE;
        return Math.floorDiv(groupNotAfter - nowSecs, 86400L);
    }

    /**
     * Whole days left on the client's certificate, with the conventions of
     * {@link #groupRemainingDays}. RCC.16 A.4.1.5 forbids a Self-Update whose new credential is
     * already inside the 30-day floor.
     */
    public long clientRemainingDays(final long nowSecs) {
        if (clientNotAfter <= 0L) return Long.MIN_VALUE;
        return Math.floorDiv(clientNotAfter - nowSecs, 86400L);
    }

    @Override public String toString() {
        return "selfLeaf[idx=" + leafIndex + " group=" + groupNotBefore + ".." + groupNotAfter
                + " client=" + clientNotBefore + ".." + clientNotAfter
                + (stale ? " STALE]" : " current]");
    }
}
