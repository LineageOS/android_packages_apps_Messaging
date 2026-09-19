/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * Whether a conversation may be upgraded to MLS now. Every guard is a silent skip, so the decision
 * names which guard stopped it; skip lines match other clients' text verbatim so traces compare.
 * See docs/mls/group-lifecycle.md.
 */
public final class MlsUpgradePolicy {

    private MlsUpgradePolicy() {}

    /** The verdict, carrying the log line for whichever guard stopped it. */
    public enum Decision {
        PROCEED(null),
        OFFLINE("Skip conversation update because device is offline."),
        DUMMY_TOKEN("DUMMY destination token encountered, skipping MLS upgrade."),
        ALREADY_MLS("Skip conversation update because conversation is already MLS."),
        NO_METADATA("Skip conversation update because conversation metadata could not be fetched."),
        NO_SELF_KEY_PACKAGES("Skip conversation update because self has not uploaded key packages."),
        NULL_GROUP_ID("The group ID is null. Skip updating conversation to MLS."),
        INITIALIZING("The group is initializing. Skip updating conversation to MLS."),
        NOT_ENOUGH_KEY_PACKAGES(null),      // formatted: names two counts
        GROUP_EXISTS("Group already exists in the Google MLS engine. Skip updating conversation to MLS."),
        /**
         * Our own line, not other clients': the claim ledger refused the key-package probe, so
         * nothing was claimed and nothing is known about the participants' pools.
         */
        CLAIM_REFUSED_BY_LEDGER("Skip conversation update because OUR OWN claim ledger refused the "
                + "key-package probe — nothing was claimed, so nothing is known about the "
                + "participants' pools. Our own line: some peers do not ration this claim."),
        /**
         * Our own line: a participant fails {@code MlsPeerGuard.allowJoiningPeer}. Checked before
         * the claim so no peer's KeyPackage is spent on a create that cannot happen;
         * {@code establishGroup} checks again.
         */
        PEER_NOT_JOINABLE("Skip conversation update because one of its participants may not be "
                + "brought into an MLS group — the create would Welcome them, so nothing is claimed "
                + "and nothing is built. Our own line: some peers have no such allowlist.");

        private final String line;

        Decision(final String line) { this.line = line; }

        /** The verbatim line, or null for the one that has to be formatted. */
        public String line() { return line; }

        public boolean proceed() { return this == PROCEED; }
    }

    /**
     * The key-package line, naming both counts. The comparison is all-or-nothing: one member
     * without a claimable package keeps the whole group off MLS.
     */
    public static String notEnoughKeyPackagesLine(final int keyPackages, final int participants) {
        return "Skip conversation update because keyPackage count " + keyPackages
                + " is less than remote participants count " + participants;
    }

    /**
     * Decide, in order: connectivity before a round trip, identity before a claim. The order
     * decides which line a failing conversation reports.
     *
     * @param destinationTokenIsDummy the {@code DUMMY} placeholder token: no real routing target
     * yet
     * @param alreadyMls              we already hold MLS state for it
     * @param metadataAvailable       the conversation's roster and group id resolved
     * @param selfHasKeyPackages      our own pool is published
     * @param rcsGroupId              the RCS group id; null or empty is the "group ID is null"
     * guard
     * @param initializing            the group is mid-creation
     * @param claimedKeyPackages      peers we claimed a key package for, or
     *                                {@link MlsClaimLedger#CLAIM_REFUSED} when nothing was claimed
     * @param engineGroupExists       the engine already holds a group under this id
     */
    public static Decision evaluate(final boolean online, final boolean destinationTokenIsDummy,
            final boolean alreadyMls, final boolean metadataAvailable,
            final boolean selfHasKeyPackages, final String rcsGroupId, final boolean initializing,
            final int claimedKeyPackages, final int remoteParticipants,
            final boolean engineGroupExists) {
        return evaluate(online, destinationTokenIsDummy, alreadyMls, metadataAvailable,
                selfHasKeyPackages, rcsGroupId, initializing, claimedKeyPackages,
                remoteParticipants, engineGroupExists, /*peersMayJoin=*/ true);
    }

    /**
     * As above, plus the joining gate; the production entry point.
     *
     * @param peersMayJoin every participant passes {@code MlsPeerGuard.allowJoiningPeer}
     */
    public static Decision evaluate(final boolean online, final boolean destinationTokenIsDummy,
            final boolean alreadyMls, final boolean metadataAvailable,
            final boolean selfHasKeyPackages, final String rcsGroupId, final boolean initializing,
            final int claimedKeyPackages, final int remoteParticipants,
            final boolean engineGroupExists, final boolean peersMayJoin) {
        // The single copy of the order: the free guards, then the claim.
        final Decision before = evaluateBeforeClaiming(online, destinationTokenIsDummy, alreadyMls,
                metadataAvailable, selfHasKeyPackages, rcsGroupId, initializing, engineGroupExists,
                remoteParticipants, peersMayJoin);
        if (!before.proceed()) return before;
        return evaluateClaim(claimedKeyPackages, remoteParticipants);
    }

    /**
     * Every guard decidable without spending a peer's KeyPackage. {@code PROCEED} here means the
     * claim is worth making; the caller then claims and asks {@link #evaluateClaim}.
     */
    public static Decision evaluateBeforeClaiming(final boolean online,
            final boolean destinationTokenIsDummy, final boolean alreadyMls,
            final boolean metadataAvailable, final boolean selfHasKeyPackages,
            final String rcsGroupId, final boolean initializing, final boolean engineGroupExists,
            final int remoteParticipants, final boolean peersMayJoin) {
        if (!online) return Decision.OFFLINE;
        if (destinationTokenIsDummy) return Decision.DUMMY_TOKEN;
        if (alreadyMls) return Decision.ALREADY_MLS;
        if (!metadataAvailable) return Decision.NO_METADATA;
        if (!selfHasKeyPackages) return Decision.NO_SELF_KEY_PACKAGES;
        if (rcsGroupId == null || rcsGroupId.isEmpty()) return Decision.NULL_GROUP_ID;
        if (initializing) return Decision.INITIALIZING;
        if (engineGroupExists) return Decision.GROUP_EXISTS;
        // A zero-participant conversation has nobody to encrypt to; 0 >= 0 would otherwise pass the
        // claim.
        if (remoteParticipants <= 0) return Decision.NO_METADATA;
        // The most specific free guard runs last so a cheaper failing guard reports its own line.
        if (!peersMayJoin) return Decision.PEER_NOT_JOINABLE;
        return Decision.PROCEED;
    }

    /**
     * What the claim came back with.
     *
     * @param claimedKeyPackages participants we hold at least one package for, or
     *     {@link MlsClaimLedger#CLAIM_REFUSED} when our own ledger refused and nothing was claimed
     */
    public static Decision evaluateClaim(final int claimedKeyPackages,
            final int remoteParticipants) {
        // Refusal is tested before the shortfall: -1 < N would otherwise blame the participants.
        if (claimedKeyPackages == MlsClaimLedger.CLAIM_REFUSED) {
            return Decision.CLAIM_REFUSED_BY_LEDGER;
        }
        if (claimedKeyPackages < remoteParticipants) return Decision.NOT_ENOUGH_KEY_PACKAGES;
        return Decision.PROCEED;
    }
}
