/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * The four rejections a fetched GroupInfo must pass before the engine sees it, and which epoch
 * identifier to trust. Checking here names the missing field instead of surfacing an mls-rs codec
 * position, and runs before the era-advance path has claimed KeyPackages or the pending slot.
 */
public final class MlsGroupInfoGate {

    /** What was wrong, or {@link #OK}. */
    public enum Verdict {
        OK,
        /** No GroupInfo at all — there is nothing to advance from. */
        EMPTY_GROUP_INFO,
        /** No ratchet_tree: a Welcome built from this fails at the peer, not here. */
        EMPTY_RATCHET_TREE,
        /** No epoch authenticator in the latest epoch — nothing to bind the commit's base to. */
        EMPTY_LATEST_EPOCH_AUTHENTICATOR,
        /** No paginated epoch identifier — the fetch is incomplete, not merely small. */
        EMPTY_PAGINATED_EPOCH_IDENTIFIER;

        public boolean ok() { return this == OK; }
    }

    /** Applies the four rejections, the GroupInfo first because the others are read out of it. */
    public static Verdict check(final byte[] groupInfo, final byte[] ratchetTree,
            final byte[] latestEpochAuthenticator, final byte[] paginatedEpochIdentifier) {
        if (isEmpty(groupInfo)) return Verdict.EMPTY_GROUP_INFO;
        if (isEmpty(ratchetTree)) return Verdict.EMPTY_RATCHET_TREE;
        if (isEmpty(latestEpochAuthenticator)) return Verdict.EMPTY_LATEST_EPOCH_AUTHENTICATOR;
        if (isEmpty(paginatedEpochIdentifier)) return Verdict.EMPTY_PAGINATED_EPOCH_IDENTIFIER;
        return Verdict.OK;
    }

    /**
     * Prefers the server's {@code latest_epoch_identifier} and falls back to the flat
     * {@code (era, epoch)} pair only when it is absent: the fetch exists because our pair may be
     * the number in question. This is the rule at engine level, pinned by its tests; production
     * applies it where the response is parsed, so do not wire a second caller.
     *
     * @return the identifier to use; never {@code null}
     */
    public static byte[] preferredEpochIdentifier(final byte[] latestEpochIdentifier,
            final byte[] eraEpochFallback) {
        if (!isEmpty(latestEpochIdentifier)) return copy(latestEpochIdentifier);
        return eraEpochFallback == null ? new byte[0] : copy(eraEpochFallback);
    }

    /** Whether the fallback was used, meaning the server named no identifier. */
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
