/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Op;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
import com.android.messaging.rcs.log.LogMask;
/**
 * Builds and sends one control commit for our own change (rekey, add, remove, self-leave), then
 * adopts it or rolls back. See docs/mls/group-lifecycle.md.
 */
public final class MlsCommitSend {
    private MlsCommitSend() {}

    /** Returns the era on acceptance, or -1. */
    public static int commitAndSend(final MlsConfig cfg, final MlsShellPort shell,
            final MlsLogSink log, final String rcsGroupId, final String peerE164,
            final byte[] addPeerKeyPackage, final byte[] memberSigPub, final Op op,
            final String what) {
        if (!shell.ensureSession()) return -1;
        final String conversationId =
                MlsGroupState.resolveInbound(shell, log, rcsGroupId, peerE164);
        if (conversationId == null) {
            log.w("MlsCommitSend: " + what + " — no group for " + LogMask.number(peerE164));
            return -1;
        }
        final Group g = shell.getGroup(conversationId);
        if (g == null || g.groupId == null) return -1;
        // Cleared before the attempt, so an early return never leaves a previous verdict behind.
        final ConvState controlState = shell.conv(conversationId);
        synchronized (controlState) {
            controlState.lastControlVerdict = -1;
            controlState.lastControlDetail = null;
        }
        if (!MlsServerBundle.membershipChangeAllowedByFloor(cfg, shell.session(), log,
                conversationId, g, op, what)) return -1;
        // The server validates the commit's base against this pre-commit authenticator.
        final byte[] baseEpochAuth = shell.session().epochAuth(g.groupId);
        // Minted before the commit: the AAD binds the message id the request carries.
        final String ctrlId = "mls-" + what + "-"
                + (rcsGroupId == null || rcsGroupId.isEmpty() ? peerE164 : rcsGroupId)
                + "-" + System.currentTimeMillis();
        final byte[] preEraEpoch = shell.session().eraEpoch(g.groupId);
        final int eraNow = MlsAppMessage.eraFrom(preEraEpoch);
        final long epochNow = MlsAppMessage.epochFrom(preEraEpoch);
        // The engine builds the AAD from this id and the group's own era.
        final byte[] aad = ctrlId.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        // The engine applies the commit before the server answers; the snapshot undoes a refusal.
        final byte[] snapshot = shell.session().exportGroupSnapshot(g.groupId);
        final MlsGroupArtifacts art;
        switch (op) {
            case ADD:    art = shell.session().addMember(g.groupId, addPeerKeyPackage, aad); break;
            case REMOVE: art = shell.session().removeMember(g.groupId,
                    memberSigPub == null ? new byte[0] : memberSigPub, aad); break;
            case LEAVE:  art = shell.session().selfLeave(g.groupId, aad); break;
            case REKEY:
            default:
                art = cfg.publishExternalPub
                        ? shell.session().selfUpdateExtPub(g.groupId, aad)
                        : shell.session().selfUpdate(g.groupId, aad);
                if (cfg.publishExternalPub) {
                    log.w("MlsCommitSend: publishing an external_pub-carrying "
                            + "GroupInfo for this commit (" + MlsConfig.KEY_PUBLISH_EXTERNAL_PUB
                            + "=1). If the server takes it, our groups become resync-joinable; if it "
                            + "answers tree-not-found, the separate-tree pairing is still wrong.");
                }
                break;
        }
        if (art == null || art.commit == null) {
            if (snapshot != null) shell.session().restoreGroupSnapshot(g.groupId, snapshot);
            log.e("MlsCommitSend: engine produced no commit for " + what
                    + " — rolled back");
            return -1;
        }
        log.i("MlsCommitSend: " + what + " commit=" + art.commit.length
                + "B groupInfo=" + (art.groupInfo == null ? 0 : art.groupInfo.length)
                + "B tree=" + (art.ratchetTree == null ? 0 : art.ratchetTree.length)
                + "B aad=" + (aad == null ? 0 : aad.length) + "B era=" + eraNow);
        // Test fixture, inert unless armed on a debug build: leaves us one epoch ahead.
        final MlsOutboundHold.Verdict holdVerdict =
                shell.outboundHoldVerdict(conversationId, op == Op.REKEY);
        if (holdVerdict == MlsOutboundHold.Verdict.REFUSE) {
            final boolean rolledBack = snapshot != null
                    && shell.session().restoreGroupSnapshot(g.groupId, snapshot);
            shell.noteOutboundHoldRefused();
            log.w("MlsCommitSend: the AHEAD fixture is AT CAPACITY ("
                    + MlsOutboundHold.CAPACITY + " commits withheld) — refusing this " + what
                    + " and rolling it back" + (rolledBack ? "" : " (ROLLBACK FAILED)")
                    + ". Nothing is lost: a rekey is our own housekeeping, and publishing it while "
                    + "the server has not seen the earlier ones would be refused anyway. The gap "
                    + "stops growing here.");
            return -1;
        }
        if (holdVerdict == MlsOutboundHold.Verdict.SUPPRESS) {
            return shell.suppressCommit(conversationId, g, op, what, ctrlId, peerE164, rcsGroupId,
                    art, baseEpochAuth, snapshot, eraNow, epochNow);
        }
        final MlsProviderRpc.ControlResult r = shell.rpc("applyMlsControl").applyMlsControl(
                peerE164, ctrlId, art.groupInfo, art.commit, art.tag, art.ratchetTree,
                baseEpochAuth, rcsGroupId);
        synchronized (controlState) {
            controlState.lastControlVerdict = (r == null)
                    ? MlsProviderRpc.ControlResult.VERDICT_TRANSPORT_FAILED : r.verdict;
            controlState.lastControlDetail = (r == null) ? null : r.detail;
        }
        if (r == null || r.verdict != MlsProviderRpc.ControlResult.VERDICT_OK) {
            // Never discard a commit on a silent outcome; a held, unresolved commit reports -1.
            final int held = MlsCommitApplication.keepUnacknowledgedCommit(shell, log,
                    conversationId, g, op, what, rcsGroupId, peerE164, r, epochNow);
            if (held != Integer.MIN_VALUE) return held;
            final boolean rolledBack = snapshot != null
                    && shell.session().restoreGroupSnapshot(g.groupId, snapshot);
            log.w("MlsCommitSend: " + what + " REFUSED → " + r
                    + (rolledBack ? " (rolled back to the pre-commit epoch)"
                                  : " (ROLLBACK FAILED — local state may be epoch-ahead)"));
            MlsCredentialUpdate.reportCredentialRefusal(cfg, shell, log, conversationId, g,
                    rcsGroupId, peerE164, r, what);
            // A connectivity loss is not a divergence: back off without running the heal path.
            if (r != null && MlsTransportDisposition.isConnectivityLoss(r.verdict)) {
                log.w("MlsCommitSend: connectivity lost during SEND of " + what
                        + " for " + MlsConversationKey.forLog(
                                MlsConversationKey.canonicalKey(rcsGroupId, peerE164))
                        + ", requesting backoff "
                        + "(verdict " + r.verdict + ") — NOT treating it as a divergence");
                if (MlsDriveLoop.maySchedule(shell, log, MlsConversationKey.canonicalKey(rcsGroupId,
                        peerE164), "an MLS send retry")) {
                    shell.scheduleRetry(rcsGroupId, peerE164, /*attempt=*/ 1);
                }
            } else {
                MlsSelfHeal.onControlRefused(shell, log, rcsGroupId, peerE164, r, what);
            }
            return -1;
        }
        final int era = MlsAppMessage.eraFrom(shell.session().eraEpoch(g.groupId));
        if (era >= 0) {
            g.era = era;
            g.epochAuth = shell.session().epochAuth(g.groupId);
            g.sendsThisEpoch = 0;
            // An add-only commit carries no UpdatePath, so it does not rotate our leaf.
            if (op != Op.ADD) g.sendsSinceLeafRotation = 0;
            MlsGroupState.putGroup(shell, log, conversationId, g);
        }
        // Hold sends until the peer converges; a leave does not advance our epoch.
        if (op != Op.LEAVE) MlsResendBudget.openGate(shell, log, conversationId);
        synchronized (controlState) { controlState.unacknowledgedCommitEpoch = -1L; }
        log.i("MlsCommitSend: " + what + " ACCEPTED → era=" + era);
        return era;
    }
}
