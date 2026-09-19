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
 * Whether a conversation may be upgraded to MLS right now — Google Messages' {@code cpwz
 * UpgradeToMlsOperation} guard set.
 *
 * <h2>Why this exists as a policy and not as a chain of ifs at the call site</h2>
 *
 * <p>Google Messages' upgrade is a BACKGROUND operation with <b>eleven</b> preconditions, and
 * <em>every one of them is a silent skip</em> — the conversation stays unencrypted and nothing is
 * surfaced to the send path. That is exactly the symptom this class was written for: our groups sat on
 * the legacy scheme and nothing complained. A guard set with that property has to be readable in one place
 * and say WHICH guard stopped it, or the next person debugging it is back to reading eleven
 * scattered conditions against a log that says nothing.
 *
 * <p>Pure and host-tested for the same reason {@link MlsMaintenancePolicy} is: the decision is the
 * part that is easy to get wrong and impossible to test on a device without driving the whole
 * stack.
 *
 * <h2>The skip reasons are Google Messages' own strings</h2>
 *
 * <p>Emitted verbatim (§20.4 / rework 13.2) so a trace diff matches them as literals. A paraphrase
 * breaks the comparison exactly when "why did this conversation not upgrade?" is the question.
 */
public final class MlsUpgradePolicy {

    private MlsUpgradePolicy() {}

    /** The verdict, carrying Google Messages' own line for whichever guard stopped it. */
    public enum Decision {
        PROCEED(null),
        OFFLINE("Skip conversation update because device is offline."),
        DUMMY_TOKEN("DUMMY destination token encountered, skipping MLS upgrade."),
        ALREADY_MLS("Skip conversation update because conversation is already MLS."),
        NO_METADATA("Skip conversation update because conversation metadata could not be fetched."),
        NO_SELF_KEY_PACKAGES("Skip conversation update because self has not uploaded key packages."),
        NULL_GROUP_ID("The group ID is null. Skip updating conversation to MLS."),
        INITIALIZING("The group is initializing. Skip updating conversation to MLS."),
        NOT_ENOUGH_KEY_PACKAGES(null),      // formatted — it names two counts
        GROUP_EXISTS("Group already exists in the Google MLS engine. Skip updating conversation to MLS."),
        /**
         * <b>OURS, not Google Messages'</b> — the one line in this enum that is not from their trace, and
         * it is marked so a trace diff does not match it as a literal.
         *
         * <p>Google Messages has no such reason because Google Messages does not ration this claim. We do: the
         * probe that feeds {@link #NOT_ENOUGH_KEY_PACKAGES} consumes one key package per
         * participant out of THEIR pools, on the conversation-open path, and
         * {@code MlsClaimLedger} bounds it. When that bound refuses, <b>nothing was claimed</b> —
         * so the counts {@code NOT_ENOUGH_KEY_PACKAGES} names do not exist, and answering with it
         * would put a statement about the participants in the log on the strength of our own rate
         * ledger. That is a refused look laundered into a wrong answer, the same defect as
         * routing a refusal into {@code Health.UNKNOWN}.
         */
        CLAIM_REFUSED_BY_LEDGER("Skip conversation update because OUR OWN claim ledger refused the "
                + "key-package probe — nothing was claimed, so nothing is known about the "
                + "participants' pools. Not Google Messages' line: Google Messages does not ration this claim."),
        /**
         * <b>OURS, not Google Messages'</b>, and marked so for the same reason as the constant above.
         *
         * <p>A create WELCOMES EVERY MEMBER, so {@code establishGroup} asks
         * {@code MlsPeerGuard.allowJoiningPeer} of every one of them before it builds anything —
         * the incident that prompted these guards was a create putting a third party's phone into
         * an MLS group, and that is why the gate sits in front of the create.
         *
         * <p><b>The gate belongs in front of the CLAIM, not merely in front of the create.</b>
         * {@code establishGroup}'s copy runs after the upgrade path has already
         * claimed a KeyPackage from every participant — including the ones it is about to refuse to
         * Welcome. Those claims are spent out of THEIR pools for an operation that cannot happen,
         * which is a smaller version of the same harm the gate exists for. Asking here costs
         * nothing: it is a pure predicate over local state, and {@code establishGroup} still asks it
         * again because the two callers arrive by different routes.
         */
        PEER_NOT_JOINABLE("Skip conversation update because one of its participants may not be "
                + "brought into an MLS group — the create would Welcome them, so nothing is claimed "
                + "and nothing is built. Not Google Messages' line: Google Messages has no such allowlist.");

        private final String line;

        Decision(final String line) { this.line = line; }

        /** Google Messages' verbatim line, or null for the one that has to be formatted. */
        public String line() { return line; }

        public boolean proceed() { return this == PROCEED; }
    }

    /**
     * Google Messages' key-package line, which names both counts.
     *
     * <p><b>The comparison is STRICT and ALL-OR-NOTHING across the roster</b> — one member without a
     * claimable key package holds the ENTIRE group on the legacy scheme. That is not an inference from the
     * wording; it is why the probe reports per member rather than a total.
     */
    public static String notEnoughKeyPackagesLine(final int keyPackages, final int participants) {
        return "Skip conversation update because keyPackage count " + keyPackages
                + " is less than remote participants count " + participants;
    }

    /**
     * Decide, in Google Messages' own order.
     *
     * <p>The ORDER is part of the contract, not a detail: Google Messages checks connectivity before it
     * spends a round trip, and identity before it spends a claim. Reordering would change which
     * line a failing conversation reports, and that line is the only evidence anyone gets.
     *
     * @param online                  is the device online
     * @param destinationTokenIsDummy the {@code DUMMY} placeholder token, which means this
     *                                conversation has no real routing target yet
     * @param alreadyMls              we already hold MLS state for it
     * @param metadataAvailable       the conversation's own metadata (roster, group id) resolved
     * @param selfHasKeyPackages      OUR pool is published — a peer cannot add us without it
     * @param rcsGroupId              the RCS group id; null/empty is the "group ID is null" guard
     * @param initializing            the group is mid-creation
     * @param claimedKeyPackages      how many peers we could claim a key package for, or
     *                                {@link MlsClaimLedger#CLAIM_REFUSED} when our own claim ledger
     *                                refused the probe and nothing was claimed at all
     * @param remoteParticipants      how many remote participants the conversation has
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
     * As above, plus the joining gate — the full order, and the one production entry point.
     *
     * @param peersMayJoin every participant passes {@code MlsPeerGuard.allowJoiningPeer}. A create
     *     Welcomes all of them, so a {@code false} here means the create cannot happen and no claim
     *     should be spent finding that out.
     */
    public static Decision evaluate(final boolean online, final boolean destinationTokenIsDummy,
            final boolean alreadyMls, final boolean metadataAvailable,
            final boolean selfHasKeyPackages, final String rcsGroupId, final boolean initializing,
            final int claimedKeyPackages, final int remoteParticipants,
            final boolean engineGroupExists, final boolean peersMayJoin) {
        // ONE COPY OF THE ORDER. This method is the composition of the two halves below and holds no
        // comparison of its own — the guards that run BEFORE a claim is spent, and the two that read
        // what the claim came back with. A second copy of the ordering is what the split would cost
        // if it were written as two independent lists, and the order is the contract (see above).
        final Decision before = evaluateBeforeClaiming(online, destinationTokenIsDummy, alreadyMls,
                metadataAvailable, selfHasKeyPackages, rcsGroupId, initializing, engineGroupExists,
                remoteParticipants, peersMayJoin);
        if (!before.proceed()) return before;
        return evaluateClaim(claimedKeyPackages, remoteParticipants);
    }

    /**
     * <b>Everything decidable WITHOUT spending a peer's KeyPackage.</b>
     *
     * <p>{@code PROCEED} from here does not mean "upgrade"; it means "no cheaper guard has an
     * answer, so the claim is worth making". The caller claims, then asks {@link #evaluateClaim}
     * — or {@link #evaluate}, which is these two in order.
     *
     * <p><b>Why the split exists at all.</b> The claim is not an input the caller HAS; it is a
     * network round trip that removes a one-time key from somebody else's device. Passing it as a
     * parameter to a single {@code evaluate} meant computing it before any of these guards had run,
     * so a conversation that was mid-operation, or whose participants may not be Welcomed, or whose
     * own group the engine already held, still paid for a claim first and was refused afterwards.
     * These guards are pure reads of local state; the one below them is not.
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
        // STRICT: fewer key packages than participants abandons the WHOLE upgrade. A zero-participant
        // conversation is not an upgrade candidate either — there is nobody to encrypt to, and
        // 0 >= 0 would otherwise sail through this guard.
        if (remoteParticipants <= 0) return Decision.NO_METADATA;
        // LAST OF THE FREE GUARDS, and it is last for the same reason the claim is after all of
        // them: it is the most specific, so a conversation that fails a cheaper one should report
        // the cheaper line. It is still free — a pure predicate over local state.
        if (!peersMayJoin) return Decision.PEER_NOT_JOINABLE;
        return Decision.PROCEED;
    }

    /**
     * <b>What the claim came back with</b>, and nothing else.
     *
     * @param claimedKeyPackages participants we hold at least one package for, or
     *     {@link MlsClaimLedger#CLAIM_REFUSED} when our own ledger refused and nothing was claimed
     * @param remoteParticipants how many remote participants the conversation has
     */
    public static Decision evaluateClaim(final int claimedKeyPackages,
            final int remoteParticipants) {
        // REFUSED BEFORE SHORT, and the order is the point. MlsClaimLedger.CLAIM_REFUSED
        // is negative precisely so it cannot be mistaken for a count, and it must be tested BEFORE
        // the comparison below — otherwise -1 < N is true and a refusal reports itself as the
        // participants being short of key packages, which is a fact about them that we never
        // established. Zero keeps its own meaning: we claimed for everybody and nobody had one.
        if (claimedKeyPackages == MlsClaimLedger.CLAIM_REFUSED) {
            return Decision.CLAIM_REFUSED_BY_LEDGER;
        }
        if (claimedKeyPackages < remoteParticipants) return Decision.NOT_ENOUGH_KEY_PACKAGES;
        return Decision.PROCEED;
    }
}
