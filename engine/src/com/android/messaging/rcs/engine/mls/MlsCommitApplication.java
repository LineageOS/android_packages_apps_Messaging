/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.List;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Op;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Look;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
import com.android.messaging.rcs.log.LogMask;
/**
 * Whether the server applied our commit, and what to do when the outcome cannot say
 * ({@link #SILENT}). A silent commit is kept: keeping one the server refused leaves us ahead, which
 * is rebuilt, while discarding one it applied leaves us behind an epoch we signed, which nothing
 * repairs. See docs/mls/health-and-recovery.md.
 */
public enum MlsCommitApplication {

    APPLIED,

    /** The server spoke and did not take it; roll back. */
    NOT_APPLIED,

    /** The outcome does not say; the caller must not roll back. */
    SILENT;

    public boolean isSilent() {
        return this == SILENT;
    }

    /** Unknown verdicts, and an unauthenticated one, are {@link #SILENT}: the safe direction. */
    public static MlsCommitApplication ofVerdict(final int verdict) {
        switch (verdict) {
            case MlsTransportDisposition.VERDICT_OK:
                return APPLIED;
            case MlsTransportDisposition.VERDICT_ERA_GAP:
            case MlsTransportDisposition.VERDICT_EXTERNAL_COMMIT_REFUSED:
            case MlsTransportDisposition.VERDICT_GROUP_ID_CHANGED:
            case MlsTransportDisposition.VERDICT_REJECTED:
                return NOT_APPLIED;
            case MlsTransportDisposition.VERDICT_NOT_REGISTERED:
            case MlsTransportDisposition.VERDICT_TRANSPORT_FAILED:
            default:
                return SILENT;
        }
    }

    /** What a server era/epoch read says about a commit whose own outcome was {@link #SILENT}. */
    public enum Reconciliation {
        /** A position, not an identity: the epoch authenticator decides. */
        SERVER_AT_OUR_EPOCH,
        /** No read can settle whether our commit is in that chain. */
        SERVER_PAST_OUR_EPOCH,
        /** Behind our post-commit epoch, or in another era. */
        SERVER_LACKS_IT,
        UNREADABLE,
        /** The commit did not move our epoch (a self-leave), so no comparison can answer. */
        INDISTINGUISHABLE
    }

    /** @param serverEraEpoch null when the read failed or the server holds no group */
    public static Reconciliation reconcile(final long[] serverEraEpoch, final long ourEra,
            final long preEpoch, final long postEpoch) {
        if (ourEra < 0L || preEpoch < 0L || postEpoch < 0L) return Reconciliation.UNREADABLE;
        if (postEpoch <= preEpoch) return Reconciliation.INDISTINGUISHABLE;
        if (serverEraEpoch == null || serverEraEpoch.length < 2) return Reconciliation.UNREADABLE;
        // era <= 0 is the provider's "no live era" sentinel.
        if (serverEraEpoch[0] <= 0L) return Reconciliation.UNREADABLE;
        // Eras are crossed by a Welcome, never a commit.
        if (serverEraEpoch[0] != ourEra) return Reconciliation.SERVER_LACKS_IT;
        if (serverEraEpoch[1] < postEpoch) return Reconciliation.SERVER_LACKS_IT;
        return serverEraEpoch[1] == postEpoch
                ? Reconciliation.SERVER_AT_OUR_EPOCH : Reconciliation.SERVER_PAST_OUR_EPOCH;
    }

    public enum Disposition {
        KEEP_AND_REPORT_SUCCESS,
        /** Keep the state but report failure, so callers' conservative arms (RCC.16 §9.5.3) run. */
        KEEP_BUT_REPORT_UNRESOLVED,
        ROLL_BACK
    }

    /** @param identity consulted only for {@link Reconciliation#SERVER_AT_OUR_EPOCH} */
    public static Disposition disposition(final MlsCommitApplication application,
            final Reconciliation reconciliation, final boolean alreadyHoldingOne,
            final MlsWelcomeAdmission.ServerState identity) {
        if (application == APPLIED) return Disposition.KEEP_AND_REPORT_SUCCESS;
        if (application != SILENT) return Disposition.ROLL_BACK;
        switch (reconciliation) {
            case SERVER_PAST_OUR_EPOCH:
                // Unresolved, not success: nothing shows our commit is in the server's chain.
                return alreadyHoldingOne
                        ? Disposition.ROLL_BACK : Disposition.KEEP_BUT_REPORT_UNRESOLVED;
            case SERVER_AT_OUR_EPOCH:
                // On a mismatch we still keep: below a forked server there is no repair, while
                // keeping reads as diverged and is rebuilt.
                if (identity == MlsWelcomeAdmission.ServerState.MATCHES) {
                    return Disposition.KEEP_AND_REPORT_SUCCESS;
                }
                return alreadyHoldingOne
                        ? Disposition.ROLL_BACK : Disposition.KEEP_BUT_REPORT_UNRESOLVED;
            case SERVER_LACKS_IT:
                return Disposition.ROLL_BACK;
            default:
                break;
        }
        // Hold only the first unacknowledged commit: an outage leaves us one epoch ahead at most.
        return alreadyHoldingOne
                ? Disposition.ROLL_BACK : Disposition.KEEP_BUT_REPORT_UNRESOLVED;
    }

    /**
     * Decides whether a non-OK commit outcome may discard the commit. Returns the era when the
     * server holds our commit, -1 when it is held and a reconcile scheduled, or
     * {@link Integer#MIN_VALUE} when the caller should roll back.
     */
    public static int keepUnacknowledgedCommit(final MlsShellPort shell, final MlsLogSink log,
            final String conversationId, final Group g,
            final Op op, final String what, final String rcsGroupId, final String peerE164,
            final MlsProviderRpc.ControlResult r, final long preEpoch) {
        final ConvState cs = shell.conv(conversationId);
        final int verdict = (r == null)
                ? MlsProviderRpc.ControlResult.VERDICT_TRANSPORT_FAILED : r.verdict;
        final MlsCommitApplication application = MlsCommitApplication.ofVerdict(verdict);
        if (!application.isSilent()) {
            synchronized (cs) { cs.unacknowledgedCommitEpoch = -1L; }
            return Integer.MIN_VALUE;
        }
        final byte[] post = shell.session().eraEpoch(g.groupId);
        final int postEra = MlsAppMessage.eraFrom(post);
        final long postEpoch = MlsAppMessage.epochFrom(post);
        long[] srv = null;
        boolean asked = false;
        if (postEpoch > preEpoch && postEra >= 0) {
            try {
                final Look<long[]> look = shell.lookServerEraEpoch(
                        MlsFetchLedger.Caller.COMMIT_OUTCOME_CHECK, conversationId, peerE164,
                        rcsGroupId);
                asked = !look.refused();
                if (asked) srv = look.orNull();
            } catch (final Throwable t) {
                log.w("MlsCommitApplication: could not read the server's era/epoch to see "
                        + "whether it took this " + what + " — HOLDING the commit, which is the "
                        + "safe direction", t);
            }
        }
        final MlsCommitApplication.Reconciliation onTheServer =
                MlsCommitApplication.reconcile(srv, postEra, preEpoch, postEpoch);
        // The authenticator look is spent only at equality, where it is decisive.
        MlsWelcomeAdmission.ServerState identity = MlsWelcomeAdmission.ServerState.UNKNOWN;
        if (onTheServer == MlsCommitApplication.Reconciliation.SERVER_AT_OUR_EPOCH) {
            try {
                identity = MlsWelcomeAdmission.serverStateCheck(shell,
                        MlsFetchLedger.Caller.COMMIT_OUTCOME_CHECK, rcsGroupId, peerE164);
            } catch (final Throwable t) {
                log.w("MlsCommitApplication: could not compare epoch authenticators to see "
                        + "whether the server's epoch is OURS for this " + what
                        + " — HOLDING, which "
                        + "is what an unanswered question gets here", t);
            }
        }
        final boolean alreadyHolding;
        synchronized (cs) { alreadyHolding = cs.unacknowledgedCommitEpoch >= 0L; }
        final MlsCommitApplication.Disposition what2do =
                MlsCommitApplication.disposition(application, onTheServer, alreadyHolding,
                        identity);
        // Says whether the read was made: a refused look reconciles like an empty read.
        final String evidence = onTheServer
                + (asked ? " (the read was made"
                        : " (no read was made — our own ledger refused it, "
                        + "so nothing was asked and nothing is concluded from the silence")
                + (onTheServer == MlsCommitApplication.Reconciliation.SERVER_AT_OUR_EPOCH
                        ? "; authenticator says " + identity : "")
                + ")";
        if (what2do == MlsCommitApplication.Disposition.ROLL_BACK) {
            synchronized (cs) { cs.unacknowledgedCommitEpoch = -1L; }
            log.w("MlsCommitApplication: " + what + " came back SILENT (" + r + ") and it "
                    + "is being ROLLED BACK anyway — " + evidence
                    + (onTheServer == MlsCommitApplication.Reconciliation.SERVER_LACKS_IT
                            ? ": the server is at era=" + srv[0] + " epoch=" + srv[1] + " and our "
                                    + "commit produced epoch=" + postEpoch + ", so it did NOT take "
                                    + "it and discarding is correct."
                            : ", and we are ALREADY holding an unacknowledged commit from epoch "
                                    + "before this one. The first is held and every later one is "
                                    + "rolled back onto it, so the gap stops growing at one epoch "
                                    + "however long the outage lasts (the AHEAD "
                                    + "fixture's capacity rule, applied to the real path)."));
            return Integer.MIN_VALUE;
        }
        // postEra, not a re-read: a re-read could report success with era -1.
        final int era = postEra;
        if (era >= 0) {
            g.era = era;
            g.epochAuth = shell.session().epochAuth(g.groupId);
            g.sendsThisEpoch = 0;
            if (op != Op.ADD) g.sendsSinceLeafRotation = 0;
            shell.putGroup(conversationId, g);
        }
        if (op != Op.LEAVE) MlsResendBudget.openGate(shell, log, conversationId);
        if (what2do == MlsCommitApplication.Disposition.KEEP_AND_REPORT_SUCCESS) {
            synchronized (cs) {
                cs.unacknowledgedCommitEpoch = -1L;
                cs.lastControlVerdict = MlsProviderRpc.ControlResult.VERDICT_OK;
                cs.lastControlDetail = null;
            }
            log.i("MlsCommitApplication: " + what + " came back SILENT (" + r + ") but the "
                    + "server is at OUR epoch (era=" + srv[0] + " epoch=" + srv[1] + ") and the "
                    + "epoch AUTHENTICATOR MATCHES — the chain is OURS, so it TOOK it. KEEPING the "
                    + "commit and reporting SUCCESS; the old code would have discarded an epoch we "
                    + "signed.");
            return era;
        }
        synchronized (cs) { cs.unacknowledgedCommitEpoch = postEpoch; }
        log.w("MlsCommitApplication: " + what + " came back SILENT (" + r + ") and we "
                + "could not establish whether the server took it — " + evidence
                + (onTheServer == MlsCommitApplication.Reconciliation.SERVER_AT_OUR_EPOCH
                        ? "; the server is at OUR epoch NUMBER but the chain is not demonstrably ours"
                        : onTheServer == MlsCommitApplication.Reconciliation.SERVER_PAST_OUR_EPOCH
                                ? "; the server is PAST our epoch, so a member committed on top of "
                                        + "something — and above equality no read can say whether "
                                        + "our commit is in that chain, so nothing is claimed"
                                : "")
                + "; we hold era=" + postEra
                + " epoch=" + postEpoch + ", was epoch=" + preEpoch + "). HOLDING the commit and "
                + "reconciling later rather than discarding it: keeping one the server refused "
                + "leaves us AHEAD, which the reconcile ladder rebuilds, while discarding one the "
                + "server took leaves us BEHIND an epoch we signed, which nothing repairs. "
                + "REPORTED AS FAILED to the caller, which is the honest answer to a "
                + "different question — we kept the state, we did not learn the outcome — so every "
                + "caller's conservative arm still runs.");
        if (MlsDriveLoop.maySchedule(shell, log, MlsConversationKey.canonicalKey(rcsGroupId,
                peerE164), "an MLS unacknowledged-commit reconcile")) {
            shell.scheduleRetry(rcsGroupId, peerE164, /*attempt=*/ 1);
        }
        return -1;
    }

    /**
     * @return true if this result advanced the conversation, so the caller runs the post-apply tail
     * @throws IllegalStateException for an engine status with no arm
     */
    public static boolean applyOneInboundResult(final MlsShellPort shell, final MlsLogSink log,
            final String conversationId,
            final MlsSession.ProcResult pr, final String fromE164, final String rcsGroupId,
            final byte[] mlsBytes, final String messageId) {
        switch (pr.status) {
            case 0:     // APP payload on a control frame: the RCC.16 §7.8.1 key delivery
                // Only host-assembled RccMlsBody framing is recognised; anything else is dropped.
                // TODO: recognise engine-assembled key deliveries from other clients.
                if (pr.payload != null && pr.payload.length > 0) {
                    final RccMlsBody.Parsed body = RccMlsBody.parse(pr.payload);
                    final String ct = (body == null) ? null : body.contentType;
                    if (ct != null && ct.startsWith(RccFileInfo.CONTENT_TYPE)) {
                        final boolean stored = MlsGroupMetadata.onFileInfo(shell, log, rcsGroupId,
                                fromE164, body.body);
                        log.i("MlsCommitApplication: inbound KEY DELIVERY from "
                                + LogMask.number(fromE164) + " (" + body.body.length
                                + "B FileInfo) stored="
                                + stored);
                    } else {
                        log.w("MlsCommitApplication: control-plane APP payload from "
                                + LogMask.number(fromE164) + " ct=" + ct
                                + " — NOT recognised as a key delivery, so"
                                + " it is DROPPED. For some peers the cause is that we"
                                + " expect a host-assembled RccMlsBody frame while their payload is"
                                + " engine-assembled, and these bytes are the evidence for that:"
                                + " " + pr.payload.length + "B payload (content not logged).");
                    }
                }
                return true;
            case 1:     // COMMIT applied, epoch advanced; anything we buffered may now apply
                // Not onInboundProposal: it would clear proposals a pending self_remove needs. The
                // commit moves the commitment a held subject or icon key is filed under.
                MlsGroupMetadata.openPendingSubject(shell, log, conversationId, rcsGroupId,
                        fromE164);
                MlsGroupMetadata.openPendingIcon(shell, log, conversationId, rcsGroupId, fromE164);
                MlsInboundHold.drainDeferredControl(shell, log, conversationId, fromE164,
                        rcsGroupId);
                return true;
            case 2:     // PROPOSAL: cached by reference; someone must commit it
                MlsStateChangeGate.onInboundProposal(shell, log, conversationId, fromE164,
                        rcsGroupId, pr.proposalType);
                return true;
            case 3:     // OTHER: parsed, but not an app/commit/proposal. Logged, never silent.
                log.i("MlsCommitApplication: inbound control from " + LogMask.number(fromE164)
                        + " processed as OTHER (" + mlsBytes.length + "B, welcome="
                        + (MlsWireScan.findWelcome(mlsBytes) != null) + ") — no state change");
                return true;
            case 7:
                log.w("MlsCommitApplication: inbound control from " + LogMask.number(fromE164)
                        + " is not a valid MLSMessage (MALFORMED) — dropped");
                return false;
            case 8:
                // Future epoch: buffer, do not drop. Replays are not counted: after an era advance
                // many buffered controls are stale.
                final boolean replaying;
                replaying = !MlsDriveLoop.schedulingFor(shell, conversationId).allowsScheduling();
                if (replaying) {
                    log.i("MlsCommitApplication: a replayed control from "
                            + LogMask.number(fromE164)
                            + " still does not apply — expected for stale ones; not counted");
                    return false;
                }
                // RCC.16 §10.8: a downgraded group does not buffer out-of-order messages.
                if (MlsRecordState.isDowngradedStatus(shell, log, conversationId)) {
                    log.i("MlsCommitApplication: NOT buffering the inbound control for "
                            + MlsConversationKey.forLog(conversationId)
                            + " — the group is downgraded (ED-1a). Replaying "
                            + "it later would apply commits to a conversation that has left MLS.");
                    return false;
                }
                // RCC.16 §10.8: buffer silently, with no capacity, eviction or TTL.
                MlsInboundHold.bufferFromFuture(shell, log, conversationId, messageId, mlsBytes,
                        fromE164);
                return false;
            case 9:
                log.i("MlsCommitApplication: inbound control from " + LogMask.number(fromE164)
                        + " is PAST-epoch — already superseded, dropped");
                return false;
            default:
                // A missing arm, not "did not apply"; nothing has been touched yet. toActionKind
                // throws first on a status MlsProcStatus does not know.
                MlsHostAction.rejectUnhandled("applyOneInboundResult", MlsHostAction.of(
                        MlsProcStatus.toActionKind(pr.status),
                        "engine status " + MlsProcStatus.nameOf(pr.status)));
                return false;                        // not reached: rejectUnhandled throws
        }
    }

    public static boolean applyInboundControl(final MlsConfig cfg, final MlsShellPort shell,
            final MlsLogSink log, final String fromE164, final String messageId,
            final byte[] mlsBytes, final boolean convergenceAck, final String rcsGroupId) {
        // Before anything reads group state: resolution needs our own number, and a dropped commit
        // is not backfilled.
        final boolean session = shell.ensureSession();
        String conversationId = session
                ? MlsGroupState.resolveInbound(shell, log, rcsGroupId, fromE164) : null;
        if (convergenceAck) {
            if (conversationId != null) MlsResendBudget.onPeerConverged(shell, log, conversationId);
            return true;
        }
        final String self = shell.selfE164();
        if (fromE164 != null && !self.isEmpty() && self.equals(fromE164)) {
            log.i("MlsCommitApplication: inbound control " + MlsMessageId.forLog(messageId)
                    + " is OUR OWN, "
                    + "fanned back to us by the group (" + (mlsBytes == null ? 0 : mlsBytes.length)
                    + "B). Already applied locally when we sent it — not re-processing. This is "
                    + "normal group delivery, NOT a malformed message.");
            return true;
        }
        MlsAdvancerElection.noteHeardFrom(shell, conversationId, fromE164);
        if (!session || mlsBytes == null || mlsBytes.length == 0) return false;
        if (conversationId == null) {
            // No MLS state: probably being added. Never fall back to another group.
            conversationId = MlsWelcomeAdmission.joinFromWelcome(cfg, shell, log, rcsGroupId,
                    fromE164, mlsBytes);
            if (conversationId != null) {
                MlsWelcomeAction.requireJoinable(MlsWelcomeAction.NEW_GROUP);
                log.i("MlsCommitApplication: joined on inbound control from "
                        + LogMask.number(fromE164)
                        + " (" + MlsWelcomeAction.NEW_GROUP + ")");
                MlsRecordState.clearSelfLeftOnRejoin(shell, log, conversationId, fromE164);
                return true;
            }
            log.w("MlsCommitApplication: inbound control from " + LogMask.number(fromE164)
                    + " rcsGroupId=" + rcsGroupId + " — no group and no joinable Welcome; DROPPED"
                    + " (session=" + (session ? "open" : "NOT OPEN") + ", self="
                    + (shell.selfE164().isEmpty() ? "UNKNOWN" : "known") + "). The server does not"
                    + " backfill commits, so if this was a commit we are now permanently behind on"
                    + " this conversation and only a re-add or END_MLS recovers it.");
            return false;
        }
        // Test fixture gate, inert unless a debug arm names this conversation on a debug build.
        if (shell.offerInboundHold(conversationId, fromE164, messageId, mlsBytes)) {
            return false;
        }
        // A Welcome for a group we are in: before processing, which cannot consume a Welcome.
        if (MlsWireScan.findWelcome(mlsBytes) != null
                && MlsWelcomeAdmission.rejoinOnEraAdvance(cfg, shell, log, conversationId,
                        rcsGroupId, fromE164, mlsBytes)) {
            return true;
        }

        final Group g = shell.getGroup(conversationId);
        if (g == null || g.groupId == null) return false;

        // RCC.16 §10.8: park before decrypting while mid-transition; processing advances ratchets
        // even when it fails.
        if (MlsInboundHold.bufferInboundIfGroupLocked(shell, log, conversationId, messageId,
                mlsBytes, fromE164)) {
            return false;
        }

        final boolean commitPending;
        shell.lock(conversationId);
        try {
            final int beforeEra = MlsAppMessage.eraFrom(shell.session().eraEpoch(g.groupId));
            final java.util.List<MlsSession.ProcResult> prs = MlsResultBundle.inboundResults(
                    shell.session(), log, g.groupId, mlsBytes,
                    "inbound:" + conversationId + ":" + messageId, fromE164);
            if (prs == null || prs.isEmpty()) {
                log.w("MlsCommitApplication: inbound control from " + LogMask.number(fromE164)
                        + " — engine could not process it (no status); NOT applied");
                return false;
            }
            // The shared tail below runs once, and only if some result advanced the conversation.
            boolean anyApplied = false;
            for (final MlsSession.ProcResult pr : prs) {
                if (MlsCommitApplication.applyOneInboundResult(shell, log, conversationId, pr,
                        fromE164, rcsGroupId, mlsBytes, messageId)) {
                    anyApplied = true;
                }
            }
            if (prs.size() > 1) {
                log.i("MlsCommitApplication: the engine returned " + prs.size()
                        + " results for one inbound control from " + LogMask.number(fromE164) + "; "
                        + (anyApplied ? "at least one advanced the conversation"
                                      : "none advanced the conversation"));
            }
            if (!anyApplied) {
                // Diagnostic only: classify the rejection from the epoch on the wire.
                final long ourEpoch = MlsAppMessage.epochFrom(shell.session().eraEpoch(g.groupId));
                final long theirEpoch = MlsWireScan.epochOf(mlsBytes);
                if (theirEpoch >= 0 && ourEpoch >= 0 && theirEpoch > ourEpoch) {
                    log.i("MlsCommitApplication: inbound control " + MlsMessageId.forLog(messageId)
                            + " is at "
                            + "epoch " + theirEpoch + " and we are at " + ourEpoch + " — FROM THE "
                            + "FUTURE, so it cannot apply yet. §10.8's park has already been offered "
                            + "it on this path; check the 'parked at' line for the verdict. We are "
                            + "missing the commit(s) between " + ourEpoch + " and " + theirEpoch
                            + ", and the server does not backfill commits — so if they never arrive, "
                            + "the way home is a re-add or END_MLS, not another repair attempt.");
                } else if (theirEpoch >= 0 && ourEpoch >= 0 && theirEpoch < ourEpoch) {
                    log.i("MlsCommitApplication: inbound control " + MlsMessageId.forLog(messageId)
                            + " is at "
                            + "epoch " + theirEpoch + ", already PAST ours (" + ourEpoch + ") — "
                            + "dropping. Nothing to do with a rung we have already climbed.");
                } else if (theirEpoch >= 0 && ourEpoch >= 0) {
                    log.i("MlsCommitApplication: inbound control " + MlsMessageId.forLog(messageId)
                            + " is at "
                            + "epoch " + theirEpoch + ", exactly where we already are — ALREADY "
                            + "PROCESSED (a redelivery, or a fork we cannot take). Some peers name this "
                            + "82 EpochAlreadyProcessed; it is not a missing rung.");
                } else {
                    log.w("MlsCommitApplication: inbound control " + MlsMessageId.forLog(messageId)
                            + " did not "
                            + "apply and its epoch could not be READ (theirs=" + theirEpoch
                            + " ours=" + ourEpoch
                            + "). Distinct from the already-processed case: if "
                            + "this recurs, look at the wire parse, not at the ladder.");
                }
                return false;
            }
            final byte[] ee = shell.session().eraEpoch(g.groupId);
            final int afterEra = MlsAppMessage.eraFrom(ee);
            final long afterEpoch = MlsAppMessage.epochFrom(ee);
            if (afterEra >= 0) {
                g.era = afterEra;
                g.epochAuth = shell.session().epochAuth(g.groupId);
                g.sendsThisEpoch = 0;
                // sendsSinceLeafRotation is not reset: a peer's commit does not rotate our leaf.
                MlsGroupState.putGroup(shell, log, conversationId, g);
                MlsServerBundle.auditRosterAfterPeerCommit(shell.session(), log, g, messageId,
                        fromE164);
            }
            final StringBuilder statuses = new StringBuilder();
            for (final MlsSession.ProcResult one : prs) {
                if (statuses.length() > 0) statuses.append(',');
                statuses.append(MlsProcStatus.nameOf(one.status));
            }
            log.i("MlsCommitApplication: applied inbound control from " + LogMask.number(fromE164)
                    + " status=[" + statuses + "] era " + beforeEra + "→" + afterEra
                    + " epoch=" + afterEpoch);
            if (shell.session().endMlsPresent(g.groupId)) {
                // DoneEndMls where the state table allows it; mid-heal, CannotHealDuringEndMls.
                if (MlsRecordState.moveHealth(shell, log, conversationId,
                        MlsHealthStates.DONEENDMLS, "the applied commit carries end_mls") == null
                        && MlsRecordState.recordFor(shell, log, conversationId) != null
                        && MlsRecordState.recordFor(shell, log, conversationId).healthStatus
                        != MlsHealthStates.DONEENDMLS) {
                    MlsRecordState.moveHealth(shell, log, conversationId,
                            MlsHealthStates.CANNOTHEALDURINGENDMLS,
                            "end_mls arrived mid-operation — wedged in downgrade");
                }
                // Recorded as unexpected (eligible for re-upgrade); no end_mls commit of our own.
                log.i("Found end mls proposal in output from message: "
                        + MlsConversationKey.forLog(conversationId));
                MlsDowngradeFlow.downgradeLocally(shell, log, conversationId,
                        MlsDowngradeReason.RECEIVED_END_MLS_COMMIT,
                        MlsDowngradeReason.eagerFor(MlsDowngradeReason.RECEIVED_END_MLS_COMMIT));
                log.i("MlsCommitApplication: the applied commit carries end_mls — "
                        + MlsConversationKey.forLog(conversationId)
                        + " is now UNENCRYPTED (a peer ended MLS)");
            } else {
                MlsRecordState.moveHealth(shell, log, conversationId, MlsHealthStates.HEALTHY,
                        "inbound control applied, era " + beforeEra + "→" + afterEra);
            }
            MlsResendBudget.onPeerConverged(shell, log, conversationId);
            // A cached proposal (a peer's self_remove) takes effect only once a member commits it.
            final MlsConversationRecord rec = MlsRecordState.recordFor(shell, log, conversationId);
            final MlsPendingOperation p = (rec == null) ? null : rec.pendingOperation;
            commitPending = p != null
                    && (p.kind == MlsPendingOperation.Kind.COMMIT_PENDING_PROPOSALS
                        || p.kind == MlsPendingOperation.Kind.END_MLS);
            MlsRecoveryPolicy.noteForwardProgress(shell, log, conversationId,
                    "inbound control applied");
        } catch (final IllegalStateException t) {
            // A missing arm for an engine status: a defect, logged apart from ordinary failures.
            log.e("MlsCommitApplication: UNHANDLED MLS RESULT applying inbound control "
                    + "from " + LogMask.number(fromE164)
                    + " — this control is dropped and the conversation may "
                    + "drift. Add the missing arm; do not widen a default.", t);
            return false;
        } catch (final Throwable t) {
            log.w("MlsCommitApplication: applying inbound control failed", t);
            return false;
        } finally { shell.unlock(conversationId); }
        // Now, not on the next send, or the leaving peer stays a member indefinitely.
        if (commitPending) {
            MlsStateChangeGate.commitPendingProposals(shell, log, g.rcsGroupId, g.peerE164);
        }
        return true;
    }
}
