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
 * One leaf of a SERIALIZED ratchet tree — index, whose it is, and the certificate window it carries
 *.
 *
 * <h2>Why the MSISDN travels with the window</h2>
 *
 * <p>A leaf index alone cannot answer either question this instrument exists for — <i>"is MY leaf in
 * the server's tree at all"</i> and <i>"what is THIS member's window"</i>. Indices are positions in
 * a tree that is rebuilt by every era advance, so matching them across two calls is how two readings
 * of the same roster drift apart. The engine emits both in one record for the same reason
 * {@link MlsSelfLeafStatus} reads both certificates in one place.
 *
 * <h2>Unreadable is a value, not an omission</h2>
 *
 * <p>A leaf whose credential is not X.509, or which will not parse, arrives with
 * {@code notBefore == notAfter == 0} and an empty MSISDN rather than being dropped, exactly as
 * {@link OpenMlsSession#memberValidity} reports it. "We could not read this leaf" and "this leaf is
 * fine" must never look alike — a shortened roster is indistinguishable from a healthy one.
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

    /** True when neither bound could be read. NOT the same as expired, and never treated as it. */
    public boolean unreadable() {
        return notBeforeSecs == 0L && notAfterSecs == 0L;
    }

    /**
     * Whole days of credential life left at {@code nowSecs}; negative once lapsed.
     *
     * <p>{@link Long#MIN_VALUE} when the window is unreadable, so it can never be mistaken for a
     * small number and silently compared against a floor.
     */
    public long remainingDays(final long nowSecs) {
        if (unreadable()) return Long.MIN_VALUE;
        return Math.floorDiv(notAfterSecs - nowSecs, 86400L);
    }

    /**
     * Is this leaf the identity {@code e164} names? This method exists
     * because its absence shipped a defect.
     *
     * <h3>Why a bare {@code equals} is WRONG here, measured on device</h3>
     *
     * <p>{@link #msisdn} arrives from the engine's {@code leaf_san_msisdn}, which normalises through
     * {@code normalize_e164} and <b>strips the {@code +}</b>: the leaf reads {@code 15715550104}.
     * The identity a caller holds ({@code mSelfE164}) carries it: {@code +15715550104}. So
     * {@code msisdn.equals(selfE164)} is false on every leaf of every group for every device — a
     * comparison that CANNOT PASS, which is the mirror of a check that cannot fail and just as
     * useless. It shipped, and the branch it gated told an operator to re-add a member that was
     * already in the tree.
     *
     * <p><b>Delegates to {@link RccIdentity#msisdnEquals}</b> rather than restating the rule. The
     * first fix here carried its own normaliser — a verbatim copy of
     * {@code RccIdentity.normalizeE164}, and a THIRD spelling of a rule this codebase already states
     * twice in Java and once in Rust. Four copies of an identity rule drift the moment one is
     * edited, and the drift would be invisible: each copy keeps passing its own tests.
     * {@code RccIdentity} already refuses an empty identity — which is exactly what is wanted here,
     * so a leaf rendering as {@code (no readable SAN)} cannot compare equal to us.
     *
     * <p>Comparison-time only. Nothing is normalised into storage, so the group's own framing is
     * never rewritten.
     */
    public boolean isIdentity(final String e164) {
        return RccIdentity.msisdnEquals(msisdn, e164);
    }

    @Override public String toString() {
        return "leaf=" + leafIndex + (msisdn.isEmpty() ? "" : " " + msisdn)
                + (unreadable() ? " UNREADABLE"
                        : " notBefore=" + notBeforeSecs + " notAfter=" + notAfterSecs);
    }
}
