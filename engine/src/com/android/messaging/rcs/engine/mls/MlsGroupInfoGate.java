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
 * The four hard rejections a fetched GroupInfo must pass BEFORE the engine sees it (invariant 25),
 * and which epoch identifier to trust (invariant 26) — rework item 9.6.
 *
 * <h2>Why before the engine, and not just "the engine will fail anyway"</h2>
 *
 * <p>It will, but not usefully. A truncated or empty artefact reaches mls-rs as a parse error whose
 * message names a codec position, not the field that was missing — and on the era-advance path the
 * call that fails has already claimed a key package per member and taken the pending slot. Checking
 * here turns "SerializationError at offset 4" into "the server sent no ratchet_tree", which is the
 * difference between a diagnosable server response and a morning of reading hex.
 *
 * <p>Our check today is {@code serverGi != null && serverGi.length > 0} on one of the four.
 */
public final class MlsGroupInfoGate {

    /** What was wrong, or {@link #OK}. */
    public enum Verdict {
        OK,
        /** No GroupInfo at all — there is nothing to advance from. */
        EMPTY_GROUP_INFO,
        /**
         * No ratchet_tree. The tree is what lets a joiner resolve its own leaf; without it a Welcome
         * built from this fails {@code RatchetTreeNotFound} at the far end, i.e. on the PEER rather
         * than here, which is the worst place to discover it.
         */
        EMPTY_RATCHET_TREE,
        /** No epoch authenticator in the latest epoch — nothing to bind the commit's base to. */
        EMPTY_LATEST_EPOCH_AUTHENTICATOR,
        /** No paginated epoch identifier — the fetch is incomplete, not merely small. */
        EMPTY_PAGINATED_EPOCH_IDENTIFIER;

        public boolean ok() { return this == OK; }
    }

    /**
     * Apply invariant 25's four rejections.
     *
     * <p>Order is deliberate: the GroupInfo first, because the other three are read out of it and
     * reporting a missing tree when the whole structure is absent points the reader at the wrong
     * thing.
     */
    public static Verdict check(final byte[] groupInfo, final byte[] ratchetTree,
            final byte[] latestEpochAuthenticator, final byte[] paginatedEpochIdentifier) {
        if (isEmpty(groupInfo)) return Verdict.EMPTY_GROUP_INFO;
        if (isEmpty(ratchetTree)) return Verdict.EMPTY_RATCHET_TREE;
        if (isEmpty(latestEpochAuthenticator)) return Verdict.EMPTY_LATEST_EPOCH_AUTHENTICATOR;
        if (isEmpty(paginatedEpochIdentifier)) return Verdict.EMPTY_PAGINATED_EPOCH_IDENTIFIER;
        return Verdict.OK;
    }

    /**
     * Invariant 26: prefer the {@code latest_epoch_identifier}; fall back to the flat
     * {@code (era, epoch)} pair only when it is absent.
     *
     * <p>The preference is not a tie-break between two spellings of one fact. The identifier is what
     * the SERVER considers current, while {@code (era, epoch)} is what we last computed — and the
     * whole reason we are fetching is that those may disagree. Reading our own pair when the server
     * offered its own answer means resolving a divergence with the very number that is in question.
     *
     * <h2>NOT the production implementation — do not wire this a second time</h2>
     *
     * <p>Invariant 26 is IMPLEMENTED, provider-side, in {@code MlsGroupProtos.parseServerEra} and
     * {@code parseServerEpoch}, because the choice has to be made over the raw response proto
     * where the two sources actually live: the identifier at field 7, the flat pair at fields 2 and
     * 3 as bare top-level varints. This method is the rule stated at the level the engine module can
     * see, kept because its tests are where the rule is pinned; it is not a seam waiting for a
     * caller, and treating it as one would give us two implementations of one invariant.
     *
     * <p>It also cannot express all of the rule. Google Messages keys BOTH components off one presence bit,
     * so a PRESENT-but-partial identifier is authoritative and yields {@code 0} for whatever it
     * omits, rather than being patched from the flat pair. That is per-component behaviour and this
     * signature is per-blob.
     *
     * @return the identifier to use; never {@code null}
     */
    public static byte[] preferredEpochIdentifier(final byte[] latestEpochIdentifier,
            final byte[] eraEpochFallback) {
        if (!isEmpty(latestEpochIdentifier)) return copy(latestEpochIdentifier);
        return eraEpochFallback == null ? new byte[0] : copy(eraEpochFallback);
    }

    /** Whether the fallback was used — worth logging, because it means the server named none. */
    public static boolean usedFallback(final byte[] latestEpochIdentifier) {
        return isEmpty(latestEpochIdentifier);
    }

    private static boolean isEmpty(final byte[] b) { return b == null || b.length == 0; }

    private static byte[] copy(final byte[] b) {
        final byte[] out = new byte[b.length];
        System.arraycopy(b, 0, out, 0, b.length);
        return out;
    }

    private MlsGroupInfoGate() {}
}
