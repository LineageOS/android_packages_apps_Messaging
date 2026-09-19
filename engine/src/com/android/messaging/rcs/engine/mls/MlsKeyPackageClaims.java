/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.List;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Claim;
import com.android.messaging.rcs.log.LogMask;
/**
 * Claims peers' KeyPackages for an operation: whom to ask, what a claim-ledger refusal means for
 * the whole operation, and how the answer is logged. The claims themselves are provider calls
 * reached through {@link MlsShellPort}.
 */
public final class MlsKeyPackageClaims {
    private MlsKeyPackageClaims() {}

    /**
     * Logs an empty-claim refusal whose wording {@link MlsClaimLedger} decided. Kept separate so
     * callers under a method-size guard do not grow when the wording changes.
     */
    public static void logClaimBlocked(final MlsLogSink log, final String line) {
        log.w("MlsKeyPackageClaims: " + line);
    }

    /**
     * The upgrade's one claim: the input to its all-or-nothing guard and the packages the create
     * is built from. Claiming consumes a KeyPackage per device of each participant, so callers run
     * every cheaper guard first, and the create consumes these rather than claiming again.
     */
    public static MlsUpgradeClaim claimForUpgrade(final MlsShellPort shell, final MlsLogSink log,
            final java.util.List<String> participants) {
        final java.util.LinkedHashMap<String, java.util.List<byte[]>> byPeer =
                new java.util.LinkedHashMap<>();
        if (!shell.ensureSession() || participants == null || participants.isEmpty()) {
            return MlsUpgradeClaim.claimed(byPeer);
        }
        for (final String m : participants) {
            try {
                final Claim<java.util.List<byte[]>> claim =
                        shell.claimAll(MlsClaimLedger.Caller.UPGRADE_PROBE, m);
                if (claim.refused()) {
                    // A ledger refusal abandons the whole claim: a partial count would read as "not
                    // enough key packages", and further claims would spend peers' pools for
                    // nothing. Packages in hand are dropped so the create cannot build a short
                    // group.
                    log.w("MlsKeyPackageClaims: the upgrade claim is ABANDONED after "
                            + byPeer.size() + " of " + participants.size()
                            + " participant(s) — the "
                            + "claim ledger refused " + LogMask.number(m)
                            + ", and a partial count would read as "
                            + "'not enough key packages', which is a statement about the "
                            + "participants we did not make. " + claim.why());
                    return MlsUpgradeClaim.refusedByLedger(claim.why());
                }
                // Recorded even when empty: "the KDS served nothing" is evidence about the peer.
                final java.util.List<byte[]> perDevice = claim.orNull();
                byPeer.put(m, (perDevice == null)
                        ? new java.util.ArrayList<byte[]>() : perDevice);
            } catch (final Throwable ignored) {
                // A claim that threw did not happen; left out rather than recorded empty.
            }
        }
        return MlsUpgradeClaim.claimed(byPeer);
    }

    /**
     * Every device's KeyPackage for every member, for the initial commit, or {@code null} to refuse
     * the create. A member with no package, a refused claim or a package below the RCC.16 A.4.1.2
     * floor refuses the whole create: the server checks the MLS group against the full RCS roster,
     * and an omitted member or device could not read the conversation.
     *
     * @param preClaimed packages the upgrade path already claimed, consumed here; {@code null}
     *     means claim now
     */
    public static java.util.List<byte[]> gatherInitialKeyPackages(final MlsShellPort shell,
            final MlsLogSink log, final List<String> members, final MlsUpgradeClaim preClaimed) {
        // A refused pre-claim must not fall through to a live claim that spends what it withheld.
        if (preClaimed != null && preClaimed.refused()) {
            log.w("MlsKeyPackageClaims: NOT creating the group — the pre-claim handed in "
                    + "was REFUSED by our own claim ledger, and claiming again here would spend "
                    + "exactly what the refusal withheld. " + preClaimed.why());
            return null;
        }
        final java.util.List<byte[]> kps = new java.util.ArrayList<>();
        for (final String m : members) {
            final java.util.List<byte[]> perDevice;
            // Unknown unless a live claim for this member happened.
            MlsClaimLedger.Attribution blame = MlsClaimLedger.Attribution.UNKNOWN;
            if (preClaimed != null && preClaimed.covers(m)) {
                // Consumed, not read: a KeyPackage is single-use (RFC 9420 §10).
                perDevice = preClaimed.take(m);
            } else {
                if (preClaimed != null) {
                    // The pre-claim and the create disagree about the roster; name the member.
                    log.w("MlsKeyPackageClaims: the pre-claim carries no answer about "
                            + LogMask.number(m)
                            + " — claiming live for them. The upgrade path claimed for a "
                            + "different member list than the one the create was given.");
                }
                final Claim<java.util.List<byte[]>> claim =
                        shell.claimAll(MlsClaimLedger.Caller.GROUP_ESTABLISH, m);
                if (claim.refused()) {
                    // Our ledger refused; this says nothing about the member's pool.
                    log.w("MlsKeyPackageClaims: NOT creating the group — our own claim "
                            + "ledger refused the KeyPackage claim for " + LogMask.number(m)
                            + ", so we never asked "
                            + "and this says nothing about their pool. Retried from the next open. "
                            + claim.why());
                    return null;
                }
                blame = claim.attribution();
                perDevice = claim.orNull();
            }
            if (perDevice == null || perDevice.isEmpty()) {
                MlsKeyPackageClaims.logClaimBlocked(log,
                        MlsClaimLedger.establishGroupBlockedLine(m, blame));
                return null;
            }
            for (final byte[] k : perDevice) {
                if (k == null || k.length == 0) continue;
                if (!shell.keyPackageUsable(k, m)) return null;
                kps.add(k);
            }
            if (perDevice.size() > 1) {
                log.i("MlsKeyPackageClaims: " + LogMask.number(m) + " has " + perDevice.size()
                        + " device(s) — all of them get a leaf in the initial commit");
            }
        }
        return kps;
    }
}
