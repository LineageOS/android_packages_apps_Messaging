/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Look;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import com.android.messaging.rcs.log.LogMask;
/**
 * The downgrade lifecycle as flows: {@code endMls}, revive (in place, or in a new era, the only way
 * out of {@code CannotHealDuringEndMls}), Phoenix mode, the host half of a downgrade, and the fast
 * re-upgrade. {@link MlsDowngradeLadder} and {@link MlsHealthPredicates} are the policy these
 * apply. See docs/mls/downgrade.md.
 */
public final class MlsDowngradeFlow {
    private MlsDowngradeFlow() {}

    /**
     * Revive by new era: an era advance with {@link MlsAdvanceEraKind#REVIVAL}, whose GroupContext
     * is the old one minus {@code end_mls}. Ends in {@code Healthy} or back in {@code DoneEndMls};
     * there is no partial outcome.
     */
    public static int reviveInNewEra(final MlsShellPort shell, final MlsLogSink log,
            final String key, final String rcsGroupId, final String peerE164, final int status) {
        if (!MlsHealthStates.isLegal(status, MlsHealthStates.ONGOINGERAADVANCEMENTFORREVIVE)) {
            log.w("MlsDowngradeFlow: cannot revive " + MlsConversationKey.forLog(key)
                    + " — neither shape is "
                    + "reachable from " + MlsHealthStates.name(status) + ". In-place needs an edge "
                    + "to OngoingReviveMls and new-era one to OngoingEraAdvancementForRevive; the "
                    + "§5.3 table has neither from here.");
            return -1;
        }
        log.i("MlsDowngradeFlow: reviving " + MlsConversationKey.forLog(key) + " by NEW ERA from "
                + MlsHealthStates.name(status) + " — the in-place shape is not reachable from this "
                + "state" + (status == MlsHealthStates.CANNOTHEALDURINGENDMLS
                        ? " (this is the wedge, and a new era is its only way back)" : ""));
        if (shell.moveHealth(key, MlsHealthStates.ONGOINGERAADVANCEMENTFORREVIVE,
                "revive by new era requested") == null
                && status != MlsHealthStates.ONGOINGERAADVANCEMENTFORREVIVE) {
            return -1;
        }
        if (!MlsPendingOperation.claimPendingOp(shell, log, key,
                MlsPendingOperation.Kind.ERA_ADVANCEMENT_FOR_REVIVE,
                MlsPendingOperation.Origin.EXPLICIT_API, "eraAdvanceForRevive")) {
            return -1;
        }
        final int era = MlsEraAdvance.eraAdvanceWithKind(shell, rcsGroupId, peerE164,
                MlsAdvanceEraKind.REVIVAL);
        if (era < 0) {
            // RevivalFailed (12 -> 10): back to a plain downgraded group, a state every path
            // handles.
            shell.moveHealth(key, MlsHealthStates.DONEENDMLS, "the revival era advance failed");
            MlsPendingOperation.retryPendingOp(shell, log, key, "the revival era advance failed");
            return -1;
        }
        shell.moveHealth(key, MlsHealthStates.HEALTHY, "revived in a new era");
        MlsPendingOperation.clearPendingOp(shell, log, key);
        final String convId = shell.conversationIdFor(key);
        if (convId != null) shell.reupgradeStore().clear(convId);
        log.i("MlsDowngradeFlow: REVIVED IN NEW ERA → era=" + era
                + "; encrypted sending is ENABLED again for this conversation");
        return era;
    }

    /**
     * Revive: the precondition ({@link MlsHealthPredicates#canRevive}, which reads the extension as
     * well as the status because that disagreement is what revive repairs), then the shape the
     * table allows. In place, the move to {@code OngoingReviveMls} must succeed before anything
     * removes {@code 0xF002}.
     */
    public static int reviveInner(final MlsShellPort shell, final MlsLogSink log, final String key,
            final Group g, final String rcsGroupId, final String peerE164) {
        final MlsConversationRecord rec = MlsRecordState.recordFor(shell, log, key);
        final int status = rec == null ? MlsHealthStates.UNKNOWN : rec.healthStatus;
        final boolean extPresent = shell.session().endMlsPresent(g.groupId);

        if (!MlsHealthPredicates.canRevive(status, extPresent)) {
            // Which clause failed decides what is logged, and whether anything is.
            if (MlsHealthPredicates.reviveRefusalIsZinniaBug(status, extPresent)) {
                log.w("MLS is already active, no need to revive. For group: "
                        + MlsGroupState.groupIdForTrace(shell, key));
                // The trace line matches other clients' logs byte for byte, embedded newline and
                // indent included.
                log.e(MlsTrace.tryingToReviveAnUnhealthyGroupBug(
                        MlsHealthStates.name(status)));
            } else if (!extPresent && status != MlsHealthStates.CANNOTHEALDURINGENDMLS) {
                log.i("MLS is already active, no need to revive. For group: "
                        + MlsGroupState.groupIdForTrace(shell, key));
            }
            // Failing clause two is silent by design.
            return -1;
        }

        // Two shapes, chosen by the table (see docs/mls/downgrade.md): in place 10 -> 11, or new
        // era -> 12. CannotHealDuringEndMls has no edge to 11, so a new era is its only way back.
        if (!MlsHealthStates.isLegal(status, MlsHealthStates.ONGOINGREVIVEMLS)) {
            return MlsDowngradeFlow.reviveInNewEra(shell, log, key, rcsGroupId, peerE164, status);
        }

        // Transition before remove: a refused move leaves 0xF002 in place.
        if (shell.moveHealth(key, MlsHealthStates.ONGOINGREVIVEMLS, "revive requested") == null
                && status != MlsHealthStates.ONGOINGREVIVEMLS) {
            log.w("MlsDowngradeFlow: NOT reviving " + MlsConversationKey.forLog(key)
                    + " — the transition to "
                    + "OngoingReviveMls was refused from " + MlsHealthStates.name(status)
                    + ", so end_mls stays in place (invariant 96). A failed transition must never "
                    + "leave a group un-downgraded.");
            return -1;
        }
        if (!MlsPendingOperation.claimPendingOp(shell, log, key,
                MlsPendingOperation.Kind.REVIVE_MLS,
                // Named, so a held slot is attributable.
                MlsPendingOperation.Origin.EXPLICIT_API, "reviveMls")) {
            log.w("MlsDowngradeFlow: revive could not claim the slot for "
                    + MlsConversationKey.forLog(key));
            return -1;
        }

        final byte[] snapshot = shell.session().exportGroupSnapshot(g.groupId);
        final byte[] baseEpochAuth = shell.session().epochAuth(g.groupId);
        final String ctrlId = "mls-revive-" + (rcsGroupId == null ? peerE164 : rcsGroupId)
                + "-" + System.currentTimeMillis();
        final int eraNow = MlsAppMessage.eraFrom(shell.session().eraEpoch(g.groupId));
        // AAD built by the engine from this id and the era
        final byte[] aad = ctrlId.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        final MlsGroupArtifacts art =
                shell.session().commitEndMls(g.groupId, aad, /*resume=*/ true);
        if (art == null || art.commit == null) {
            if (snapshot != null) shell.session().restoreGroupSnapshot(g.groupId, snapshot);
            log.e("MlsDowngradeFlow: revive commit could not be built — rolled back");
            MlsPendingOperation.retryPendingOp(shell, log, key,
                    "the revive commit could not be built");
            return -1;
        }
        final MlsProviderRpc.ControlResult r = shell.rpc("applyMlsControl").applyMlsControl(
                peerE164, ctrlId, art.groupInfo, art.commit, art.tag, art.ratchetTree,
                baseEpochAuth, rcsGroupId);
        if (r == null || r.verdict != MlsProviderRpc.ControlResult.VERDICT_OK) {
            final boolean rolledBack = snapshot != null
                    && shell.session().restoreGroupSnapshot(g.groupId, snapshot);
            log.w("MlsDowngradeFlow: revive REFUSED → " + r
                    + (rolledBack ? " (rolled back)" : " (ROLLBACK FAILED)"));
            MlsPendingOperation.retryPendingOp(shell, log, key,
                    "the server refused the revive commit");
            MlsSelfHeal.onControlRefused(shell, log, rcsGroupId, peerE164, r, "revive");
            return -1;
        }
        final int era = MlsAppMessage.eraFrom(shell.session().eraEpoch(g.groupId));
        if (era >= 0) {
            g.era = era;
            g.epochAuth = shell.session().epochAuth(g.groupId);
            g.sendsThisEpoch = 0;
            // The revive commit carries an UpdatePath, so our leaf rotates and the usage counter
            // resets.
            g.sendsSinceLeafRotation = 0;
            shell.putGroup(key, g);
        }
        shell.moveHealth(key, MlsHealthStates.HEALTHY, "revived from end_mls");
        MlsPendingOperation.clearPendingOp(shell, log, key);
        // Encrypted again: clear the re-upgrade bookkeeping so a later downgrade computes its
        // backoff from a fresh anchor.
        final String convId = shell.conversationIdFor(key);
        if (convId != null) shell.reupgradeStore().clear(convId);
        log.i("MlsDowngradeFlow: REVIVE ACCEPTED → era=" + era
                + "; encrypted sending is ENABLED again for this conversation");
        return era;
    }

    /**
     * Re-upgrade loop 2: clears the bookkeeping when the conversation was eagerly downgraded and
     * the engine now reports exactly {@code Healthy}. No backoff and no counter. Tests the coarse
     * status on purpose, so a requested Phoenix run cannot re-upgrade itself.
     */
    public static void maybeFastReupgrade(final MlsShellPort shell, final MlsLogSink log,
            final String key) {
        final String convId = shell.conversationIdFor(key);
        if (convId == null) return;
        final MlsConversationRecord rec = MlsRecordState.recordFor(shell, log, key);
        if (rec == null) return;
        final boolean coarseHealthy = rec.healthStatus == MlsHealthStates.HEALTHY;
        if (!shell.reupgradeStore().load(convId).eligibleForFastReupgrade(coarseHealthy)) {
            return;
        }
        log.i("MlsDowngradeFlow: re-upgrading " + MlsConversationKey.forLog(key)
                + " after an eager downgrade "
                + "because it is now healthy (§9.7l loop 2 — no backoff, no counter)");
        shell.reupgradeStore().clear(convId);
    }

    /**
     * Phoenix mode: an era advance whose new era is born downgraded ({@link
     * MlsAdvanceEraKind#PHOENIX_DOWNGRADE}), for a group whose ordinary end_mls commit cannot land.
     * It needs no key packages. The app is downgraded before the advance is requested. Logged at
     * error level: reaching it means the ordinary downgrade failed.
     */
    public static int initiatePhoenixMode(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164, final String why) {
        if (!shell.ensureSession()) return -1;
        final String key = shell.resolveInbound(rcsGroupId, peerE164);
        final Group g = (key == null) ? null : shell.getGroup(key);
        if (g == null || g.groupId == null) return -1;
        log.e("MlsDowngradeFlow: " + (why == null ? "Phoenix mode requested" : why)
                + " — Potential MLS bug detected. Requesting a Phoenix mode era downgrade for "
                + MlsConversationKey.forLog(key)
                + ". Reaching here means the ordinary end_mls commit could not be landed.");

        final MlsConversationRecord rec = MlsRecordState.recordFor(shell, log, key);
        final int status = rec == null ? MlsHealthStates.UNKNOWN : rec.healthStatus;
        if (MlsHealthPredicates.isPhoenixOngoing(status)) {
            log.i(MlsDowngradeLadder.phoenixOngoingLine(MlsGroupState.groupIdForTrace(shell, key)));
            return -1;
        }
        shell.downgradeLocally(key,
                MlsDowngradeReason.EAGERLY_DOWNGRADE_LOCALLY_DURING_PHOENIX_MODE,
                MlsDowngradeReason.eagerFor(
                        MlsDowngradeReason.EAGERLY_DOWNGRADE_LOCALLY_DURING_PHOENIX_MODE));

        if (
                shell.moveHealth(key, MlsHealthStates.PHOENIXMODEREQUESTED,
                        "phoenix mode requested: " + why)
                == null && status != MlsHealthStates.PHOENIXMODEREQUESTED) {
            log.w("MlsDowngradeFlow: phoenix requested for " + MlsConversationKey.forLog(key)
                    + " but the "
                    + "transition from " + MlsHealthStates.name(status) + " is not in the table");
            return -1;
        }
        if (!MlsPendingOperation.claimPendingOp(shell, log, key,
                MlsPendingOperation.Kind.PHOENIX_MODE,
                MlsPendingOperation.Origin.OTHER, "phoenixDowngrade")) {
            return -1;
        }
        // StartedPhoenixMode (17 -> 16), the only inbound edge of 16.
        shell.moveHealth(key, MlsHealthStates.ONGOINGPHOENIXMODE, "phoenix era advance starting");
        final int era = MlsEraAdvance.eraAdvanceWithKind(shell, rcsGroupId, peerE164,
                MlsAdvanceEraKind.PHOENIX_DOWNGRADE);
        if (era < 0) {
            // PhoenixModeFailedSoDowngradingLocally (16 -> 15): the wedge.
            shell.moveHealth(key, MlsHealthStates.CANNOTHEALDURINGENDMLS,
                    "the phoenix era advance failed");
            MlsPendingOperation.retryPendingOp(shell, log, key, "the phoenix era advance failed");
            return -1;
        }
        // PhoenixModeSucceeded (16 -> 10): the new era is born downgraded, so this lands in
        // DoneEndMls.
        shell.moveHealth(key, MlsHealthStates.DONEENDMLS, "phoenix succeeded — the new era carries "
                + "end_mls, so the conversation is downgraded in a fresh era");
        MlsPendingOperation.clearPendingOp(shell, log, key);
        return era;
    }

    /**
     * End (or resume) MLS for a conversation, with a reason. The order is the contract: guard
     * ladder, {@code EndMlsRequested} and the slot, drain parked messages, the host half, the
     * commit (unless the reason says MLS already ended), {@code OngoingEndMls} only once the commit
     * exists, then send.
     *
     * @param resume {@code true} removes the tag (a revive), one of the two paths allowed to
     */
    public static int endMls(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164, final boolean resume,
            final MlsDowngradeReason reason) {
        if (!shell.ensureSession()) return -1;
        final String key = shell.resolveInbound(rcsGroupId, peerE164);
        final Group g = (key == null) ? null : shell.getGroup(key);
        if (g == null || g.groupId == null) {
            log.w("MlsDowngradeFlow: end_mls — no group for "
                    + (rcsGroupId == null ? LogMask.number(peerE164) : rcsGroupId));
            return -1;
        }
        if (resume) {
            return MlsDowngradeFlow.reviveInner(shell, log, key, g, rcsGroupId, peerE164);
        }
        final MlsDowngradeReason why =
                reason == null ? MlsDowngradeReason.UNKNOWN_DOWNGRADE_REASON : reason;

        // (1) The guard ladder, in fixed order.
        final MlsConversationRecord rec0 = MlsRecordState.recordFor(shell, log, key);
        final int status0 = rec0 == null ? MlsHealthStates.UNKNOWN : rec0.healthStatus;
        final MlsDowngradeLadder.Verdict verdict =
                // The reason is the request context, so this downgrade's own retained slot resumes
                // rather than being refused at guard 2.
                MlsDowngradeLadder.evaluate(status0, MlsPendingOperation.pendingOp(shell, log, key),
                        why.name());
        if (verdict.refuses()) {
            log.i(MlsDowngradeLadder.refusalLine(verdict,
                    MlsGroupState.groupIdForTrace(shell, key)));
            log.i("MlsDowngradeFlow: end_mls REFUSED for " + MlsConversationKey.forLog(key)
                    + " at ladder rung "
                    + verdict + " (status " + MlsHealthStates.name(status0) + ", reason " + why
                    + ") — this is a NO-OP, not a failure");
            // Guard 1 is idempotence: report the current era, not -1.
            return verdict == MlsDowngradeLadder.Verdict.ALREADY_DONE
                    ? MlsAppMessage.eraFrom(shell.session().eraEpoch(g.groupId)) : -1;
        }

        shell.moveHealth(key, MlsHealthStates.ENDMLSREQUESTED, "downgrade requested: " + why);
        if (!MlsPendingOperation.claimPendingOp(shell, log, key, MlsPendingOperation.Kind.END_MLS,
                MlsPendingOperation.Origin.EXPLICIT_API, why.name())) {
            log.w("MlsDowngradeFlow: end_mls could not claim the operation slot for "
                    + MlsConversationKey.forLog(key)
                    + " — another operation raced us past the ladder");
            return -1;
        }

        // (3) Drain before downgrading: a parked message decrypted after the downgrade would be
        // lost.
        log.i("Kill the request for " + MlsGroupState.groupIdForTrace(shell, key)
                + ": unlock the group and "
                + "process all pending messages, then locally downgrade.");
        MlsInboundHold.drainDeferredControl(shell, log, key, peerE164, rcsGroupId);

        // (4) The host half, stamped before any commit.
        shell.downgradeLocally(key, why, MlsDowngradeReason.eagerFor(why));

        // (5) The commit, unless the reason says MLS is already ended.
        if (why.zinniaAlreadyEnded) {
            // Column y: a second commit would downgrade a downgraded group.
            log.i("MlsDowngradeFlow: not generating an end_mls commit for "
                    + MlsConversationKey.forLog(key)
                    + " — reason " + why + " reports MLS was already ended (column y)");
            shell.moveHealth(key, MlsHealthStates.DONEENDMLS, "end_mls already applied: " + why);
            MlsPendingOperation.clearPendingOp(shell, log, key);
            return MlsAppMessage.eraFrom(shell.session().eraEpoch(g.groupId));
        }
        // (5a) Across an era gap no commit we can sign will be accepted, so the downgrade is
        // applied locally only; the server and peer are not told. Acceptable only because the
        // conversation is already diverged, which is why it is gated on an observed gap.
        final Look<long[]> gapLook =
                shell.lookServerEraEpoch(MlsFetchLedger.Caller.END_MLS, key, peerE164, rcsGroupId);
        if (gapLook.refused()) {
            // A refused look keeps the local-only gate shut and falls through to the ordinary
            // commit.
            log.i("MlsDowngradeFlow: the era-gap check that gates the LOCAL-ONLY "
                    + "downgrade for " + MlsConversationKey.forLog(key)
                    + " was refused by the fetch ledger, so the gate stays "
                    + "SHUT and the ordinary end_mls commit is attempted. The gap was not read; the "
                    + "hatch is not opened on a gap nobody read.");
        }
        final long[] srv = gapLook.orNull();
        final int localEra = MlsAppMessage.eraFrom(shell.session().eraEpoch(g.groupId));
        if (srv != null && srv[0] > 0 && localEra > 0 && srv[0] != localEra) {
            log.w("MlsDowngradeFlow: end_mls for " + MlsConversationKey.forLog(key)
                    + " cannot be COMMITTED — the "
                    + "server is at era=" + srv[0] + " and we hold era=" + localEra
                    + ", so any commit"
                    + " we can sign is one the server refuses ('Era changed from " + srv[0] + " to "
                    + localEra
                    + "'). Downgrading LOCALLY instead: this device stops encrypting the "
                    + "conversation, the server and peer are NOT told, and that is acceptable only "
                    + "because this conversation is already diverged. reason=" + why);
            shell.moveHealth(key, MlsHealthStates.DONEENDMLS,
                    "local-only end_mls across an era gap: " + why);
            MlsPendingOperation.clearPendingOp(shell, log, key);
            return localEra;
        }
        final byte[] snapshot = shell.session().exportGroupSnapshot(g.groupId);
        final byte[] baseEpochAuth = shell.session().epochAuth(g.groupId);
        final String ctrlId = "mls-endmls-" + (rcsGroupId == null ? peerE164 : rcsGroupId)
                + "-" + System.currentTimeMillis();
        final int eraNow = MlsAppMessage.eraFrom(shell.session().eraEpoch(g.groupId));
        // AAD built by the engine from this id and the era
        final byte[] aad = ctrlId.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        // The engine-request reason is computed and logged but not sent: the commit is built
        // directly and its payload is the literal "end_mls". No engine status round-trips into a
        // request context here.
        final int zinniaReason = why.zinniaReasonFor(/*contextCarriesEngineHealthStatus=*/ false);
        if (zinniaReason != 0) {
            log.i("MlsDowngradeFlow: end_mls for " + MlsConversationKey.forLog(key)
                    + " would carry engine reason "
                    + zinniaReason + " (wire " + MlsDowngradeReason.endMlsRequestWireReason(
                            zinniaReason) + ") — computed per §9.7e but NOT sent: we build the "
                    + "commit directly and EndMlsMetadata's field numbers are NEEDS-CAPTURE "
                    + "(§22.1-56). Do not guess them.");
        }
        final MlsGroupArtifacts art =
                shell.session().commitEndMls(g.groupId, aad, /*resume=*/ false);
        if (art == null || art.commit == null) {
            if (snapshot != null) shell.session().restoreGroupSnapshot(g.groupId, snapshot);
            log.e("MlsDowngradeFlow: end_mls commit could not be built — rolled back");
            MlsPendingOperation.retryPendingOp(shell, log, key,
                    "the end_mls commit could not be built");
            return -1;
        }
        // (6) OngoingEndMls only after the commit that carries 0xF002 exists.
        shell.moveHealth(key, MlsHealthStates.ONGOINGENDMLS, "end_mls commit built: " + why);
        log.i("MlsDowngradeFlow: END_MLS commit=" + art.commit.length + "B era="
                + eraNow + " reason=" + why);
        final MlsProviderRpc.ControlResult r = shell.rpc("applyMlsControl").applyMlsControl(
                peerE164, ctrlId, art.groupInfo, art.commit, art.tag, art.ratchetTree,
                baseEpochAuth, rcsGroupId);
        if (r == null || r.verdict != MlsProviderRpc.ControlResult.VERDICT_OK) {
            final boolean rolledBack = snapshot != null
                    && shell.session().restoreGroupSnapshot(g.groupId, snapshot);
            log.w("MlsDowngradeFlow: end_mls REFUSED → " + r
                    + (rolledBack ? " (rolled back)" : " (ROLLBACK FAILED)"));
            // The wedge (CannotHealDuringEndMls) only once retries are exhausted: it forbids a
            // fresh downgrade and a Phoenix run, so entering it on the first refusal would strand a
            // fixable conversation.
            final MlsPendingOperation held = MlsPendingOperation.pendingOp(shell, log, key);
            if (held != null && held.lastAttemptExhaustsTheBound()) {
                shell.moveHealth(key, MlsHealthStates.CANNOTHEALDURINGENDMLS,
                        "the end_mls commit could not be sent and the retries are exhausted");
            }
            MlsPendingOperation.retryPendingOp(shell, log, key,
                    "the server refused the end_mls commit");
            MlsSelfHeal.onControlRefused(shell, log, rcsGroupId, peerE164, r, "end_mls");
            return -1;
        }
        final int era = MlsAppMessage.eraFrom(shell.session().eraEpoch(g.groupId));
        if (era >= 0) {
            g.era = era;
            g.epochAuth = shell.session().epochAuth(g.groupId);
            g.sendsThisEpoch = 0;
            // The end_mls commit carries an UpdatePath, so our leaf rotates and the usage counter
            // resets.
            g.sendsSinceLeafRotation = 0;
            shell.putGroup(key, g);
        }
        shell.moveHealth(key, MlsHealthStates.DONEENDMLS, "the end_mls commit was accepted: "
                + why);
        MlsPendingOperation.clearPendingOp(shell, log, key);
        log.i("MlsDowngradeFlow: END_MLS ACCEPTED → era=" + era + " reason=" + why
                + "; encrypted sending is now DISABLED for this conversation");
        return era;
    }

    /**
     * End MLS for a conversation (RCC.16 §7.11.2.2): a Commit adding the {@code end_mls}
     * GroupContext extension, after which RCC.16 §9.1.1 forbids encrypted sends. Also the realistic
     * way to leave a 1:1. Uses {@code DEBUG_MENU} as the downgrade reason.
     *
     * @param resume {@code true} removes the tag and returns the conversation to encrypted
     * @return the era it was committed at, or -1
     */
    public static int endMls(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164, final boolean resume) {
        return MlsDowngradeFlow.endMls(shell, log, rcsGroupId, peerE164, resume,
                resume ? MlsDowngradeReason.UNKNOWN_DOWNGRADE_REASON
                       : MlsDowngradeReason.DEBUG_MENU);
    }

    /**
     * The host half of a downgrade: stamp the re-upgrade bookkeeping first, then (if {@code eager})
     * clear the conversation's MLS bit. Absorbs a repeat when the bit is already clear. The call
     * made once the status records the end_mls commit clears the bit whatever {@code eager} says.
     *
     * @param eager whether to clear the app's MLS bit now rather than when the commit lands
     */
    public static void downgradeLocally(final MlsShellPort shell, final MlsLogSink log,
            final String key, final MlsDowngradeReason reason, final boolean eager) {
        final String convId = shell.conversationIdFor(key);
        if (convId == null) {
            log.w("MlsDowngradeFlow: downgrade (" + reason + ") for "
                    + MlsConversationKey.forLog(key)
                    + " has no app conversation — the engine state will move but the app's "
                    + "encryption bit cannot be cleared. The conversation may keep offering MLS.");
            return;
        }
        // Idempotence: this runs twice per downgrade (the host decides, then the engine funnel
        // echoes it), and a second stamp would shift the re-upgrade backoff anchor. An unreadable
        // bit does not absorb.
        final Boolean mls = shell.conversationMlsBit(convId);
        if (mls != null && !mls) {
            log.i("MlsDowngradeFlow: conversation " + MlsConversationKey.forLog(convId) + " ("
                    + MlsConversationKey.forLog(key) + ") is "
                    + "already not MLS, cannot downgrade — absorbing the repeat (" + reason + ")");
            return;
        }
        if (mls == null) {
            log.w("MlsDowngradeFlow: could not read the encryption bits for "
                    + MlsConversationKey.forLog(convId)
                    + " — proceeding with the downgrade anyway. An unreadable bit is not evidence "
                    + "the conversation is already plaintext.");
        }
        log.i(MlsTrace.downgradingLocally(reason.name()));
        // Stamp first; the store orders reset-then-stamp inside.
        shell.reupgradeStore().markDowngrade(convId, reason, System.currentTimeMillis());
        // Column z only defers the clear until the end_mls commit lands. A status that already
        // records it (the test MlsSealSend refuses on) is the landing, so the bit goes now: kept,
        // every later send would reach a seal that refuses the conversation.
        if (!eager && !MlsRecordState.hasEndMlsStatus(shell, log, key)) {
            log.i("MlsDowngradeFlow: " + MlsConversationKey.forLog(key) + " downgrade reason "
                    + reason
                    + " suppresses the eager local downgrade (column z) — the app's MLS bit stays "
                    + "set until the commit lands");
            return;
        }
        shell.downgradeMlsScheme(convId);
        shell.reupgradeStore().setEagerlyDowngraded(convId, true);
        log.i("MlsDowngradeFlow: " + MlsConversationKey.forLog(key) + " (conversation "
                + MlsConversationKey.forLog(convId) + ") is no "
                + "longer MLS in the app — reason " + reason + ", wire " + reason.wire);
    }
}
