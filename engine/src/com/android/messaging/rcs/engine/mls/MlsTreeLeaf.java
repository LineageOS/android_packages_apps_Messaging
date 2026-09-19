/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.log.LogMask;

/**
 * One leaf of a serialized ratchet tree: index, MSISDN and certificate window, in one record so
 * the identity and the window are never matched up by position across calls. A leaf whose
 * credential cannot be read arrives with a zero window and an empty MSISDN rather than being
 * dropped, so an unreadable roster never looks like a short healthy one.
 */
public final class MlsTreeLeaf {

    /** Leaf index in the ratchet tree the server holds. */
    public final int leafIndex;
    /** {@code notBefore} of this leaf's credential, epoch seconds; {@code 0} if unreadable. */
    public final long notBeforeSecs;
    /** {@code notAfter} of this leaf's credential, epoch seconds; {@code 0} if unreadable. */
    public final long notAfterSecs;
    /** The leaf's SAN MSISDN, or empty when the leaf or its SAN could not be read. */
    public final String msisdn;

    public MlsTreeLeaf(final int leafIndex, final long notBeforeSecs, final long notAfterSecs,
            final String msisdn) {
        this.leafIndex = leafIndex;
        this.notBeforeSecs = notBeforeSecs;
        this.notAfterSecs = notAfterSecs;
        this.msisdn = msisdn == null ? "" : msisdn;
    }

    /** True when neither bound could be read; not the same as expired. */
    public boolean unreadable() {
        return notBeforeSecs == 0L && notAfterSecs == 0L;
    }

    /**
     * Whole days of credential life left at {@code nowSecs}; negative once lapsed.
     * {@link Long#MIN_VALUE} when unreadable, so it never passes a floor comparison.
     */
    public long remainingDays(final long nowSecs) {
        if (unreadable()) return Long.MIN_VALUE;
        return Math.floorDiv(notAfterSecs - nowSecs, 86400L);
    }

    /**
     * Whether this leaf is the identity {@code e164} names. The leaf's MSISDN has no {@code +}
     * while callers hold one, so this delegates to {@link RccIdentity#msisdnEquals} rather than
     * {@code equals}; an empty MSISDN never matches. Nothing is normalised into storage.
     */
    public boolean isIdentity(final String e164) {
        return RccIdentity.msisdnEquals(msisdn, e164);
    }

    @Override public String toString() {
        return "leaf=" + leafIndex + (msisdn.isEmpty() ? "" : " " + LogMask.number(msisdn))
                + (unreadable() ? " UNREADABLE"
                        : " notBefore=" + notBeforeSecs + " notAfter=" + notAfterSecs);
    }
}
