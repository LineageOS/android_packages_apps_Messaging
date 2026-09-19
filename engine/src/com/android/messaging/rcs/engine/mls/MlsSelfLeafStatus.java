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
 * OUR OWN leaf in one MLS group, compared against the certificate this client currently holds —
 * the fact the certificate-driven Self-Update is decided from.
 *
 * <h2>Why the group's copy is a separate question from the device's certificate</h2>
 *
 * <p>A group's leaf carries the credential that was current when the leaf was last written. mls-rs
 * reuses it on every subsequent commit unless a new signing identity is explicitly supplied, so a
 * device that re-mints its KDS certificate goes on presenting the OLD one to the group — and the
 * group ages on the ORIGINAL mint's clock however often the device renews. Reading the device's
 * certificate store therefore answers a question nobody is asking: the server validates the
 * ROSTER's copy (RCC.16 A.4.3.1 §1(a), Invariant 17), and this is that copy.
 *
 * <h2>{@code stale} is a certificate comparison, not a key comparison</h2>
 *
 * <p>Our subject key is derived deterministically from a stable secret, so a renewal keeps the
 * signature key and changes only the certificate. A predicate written against the key would report
 * "unchanged" for every renewal there has ever been.
 *
 * <p>Windows that could not be parsed are reported as {@code 0}, matching
 * {@link OpenMlsSession#memberValidity} — "we could not read it" must never look like "it is fine".
 */
public final class MlsSelfLeafStatus {

    /** Fixed width of the engine's record: {@code u32 + 4×u64 + u8}. */
    public static final int WIRE_BYTES = 37;

    /** Our leaf index in the group's ratchet tree. */
    public final int leafIndex;
    /** {@code notBefore} of the certificate THE GROUP holds for us, epoch seconds, 0 if unreadable. */
    public final long groupNotBefore;
    /** {@code notAfter} of the certificate THE GROUP holds for us, epoch seconds, 0 if unreadable. */
    public final long groupNotAfter;
    /** {@code notBefore} of the certificate THIS CLIENT holds, epoch seconds, 0 if unreadable. */
    public final long clientNotBefore;
    /** {@code notAfter} of the certificate THIS CLIENT holds, epoch seconds, 0 if unreadable. */
    public final long clientNotAfter;
    /** True when the two certificates differ, i.e. a Self-Update would carry a new credential in. */
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
     * Decode the engine's fixed-width record, or {@code null} if it is absent or the wrong length.
     *
     * <p>A SHORT record is null rather than a partially-filled object: every field here feeds a
     * decision to issue a Commit, and a zero that came from a truncated buffer is indistinguishable
     * from a zero that means "unreadable" once it is inside the object.
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
     * How many days of the GROUP's copy remain at {@code nowSecs} — the number RCC.16's floor is
     * stated in. Negative once it has lapsed; {@link Long#MIN_VALUE} when the window is unreadable,
     * so a caller cannot accidentally treat "we could not look" as a very old certificate.
     */
    public long groupRemainingDays(final long nowSecs) {
        if (groupNotAfter <= 0L) return Long.MIN_VALUE;
        return Math.floorDiv(groupNotAfter - nowSecs, 86400L);
    }

    /**
     * How many days the CLIENT's certificate has left at {@code nowSecs}. Same conventions as
     * {@link #groupRemainingDays}.
     *
     * <p>This is the number RCC.16 A.4.1.5 §5 gates the Self-Update on: a client must not issue one
     * whose NEW credential is already inside the 30-day floor, because the commit it produces would
     * be refused for the same reason the stale one is.
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
