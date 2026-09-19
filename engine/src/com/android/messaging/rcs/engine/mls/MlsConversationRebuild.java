/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Health;
import java.util.List;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ServerPack;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Look;
import com.android.messaging.rcs.log.LogMask;
/**
 * Rebuilds a conversation whose MLS state cannot be repaired in place: once every bound agrees,
 * forget it locally and at the provider, re-create it around the server's roster, and verify.
 * Each exit is a named {@link MlsRebuildOutcome}. See docs/mls/health-and-recovery.md.
 */
public final class MlsConversationRebuild {
    private MlsConversationRebuild() {}

    /**
     * @param prior what this recovery episode already charged to the era budget; required, with no
     *     defaulting overload, so a new recovery arm cannot inherit a charge by omission
     */
    public static MlsRebuildOutcome rebuildConversation(final MlsShellPort shell,
            final MlsLogSink log, final String rcsGroupId, final String peerE164,
            final String why, final MlsRecreationEpisode.PriorCharge prior) {
        if (!shell.ensureSession()) return MlsRebuildOutcome.COULD_NOT_ATTEMPT;
        final String key = MlsConversationKey.canonicalKey(rcsGroupId, peerE164);
        if (key == null) return MlsRebuildOutcome.COULD_NOT_ATTEMPT;
        // Resolve the roster first: after the forget there is nothing to derive it from.
        final boolean isGroupKey = rcsGroupId != null && !rcsGroupId.isEmpty();
        // One fetch, used for both the roster and the carried GroupInfo.
        final ServerPack pack =
                MlsServerBundle.serverPackForRebuild(shell, log, rcsGroupId, peerE164, key);
        final byte[] serverPack = pack.bytes();
        final java.util.List<String> roster =
                MlsServerBundle.rosterForRebuild(shell, log, rcsGroupId, peerE164, key, pack);
        if (isGroupKey && (roster == null || roster.isEmpty())) {
            log.e("MlsConversationRebuild: NOT rebuilding group " + MlsConversationKey.forLog(key)
                    + " (" + why + ") — "
                    + "the server roster is unreadable and nothing usable was recorded. Creating a "
                    + "group around a guessed member set is what produces mlsError 5 "
                    + "(mismatched-rcs-group-state), and it would leave a participant who believes "
                    + "they are in an encrypted group unable to read any of it. Refusing is the "
                    + "correct outcome here, not a missing feature.");
            return MlsRebuildOutcome.COULD_NOT_ATTEMPT;
        }
        // Asked while the conversation is intact; establishGroup asks only after the forget.
        if (isGroupKey
                && !MlsMembership.allowedToJoinAll(shell, log, "rebuild", rcsGroupId, roster)) {
            return MlsRebuildOutcome.REFUSED_BY_GUARD;
        }
        // Before any allowance is charged, so a burst (a crash loop included) is one episode.
        if (!shell.peerGuard().claimRebuildEpisode(key)) {
            log.i("MlsConversationRebuild: NOT rebuilding " + MlsConversationKey.forLog(key) + " ("
                    + why + ") — the "
                    + "episode suppressor refused; MlsPeerGuard logged which and for how long.");
            return MlsRebuildOutcome.SUPPRESSED_AS_ONE_EPISODE;
        }
        // A re-creation costs an era advance; the server is asked, and a first create is free.
        long serverEraForBudget = -1L;
        boolean serverAnswered = false;
        try {
            final Look<long[]> look =
                    shell.lookServerEraEpoch(MlsFetchLedger.Caller.REBUILD, key, peerE164,
                            rcsGroupId);
            if (look.refused()) {
                log.e("MlsConversationRebuild: NOT rebuilding " + MlsConversationKey.forLog(key)
                        + " (" + why + ") — "
                        + "the fetch ledger refused the look that establishes whether the server "
                        + "still holds this conversation. That answer is what decides whether the "
                        + "rebuild must be charged to the era budget, and an unasked question would "
                        + "be classified as a FIRST CREATE and charged nothing. "
                        + "Deferred, not abandoned.");
                return MlsRebuildOutcome.DEFERRED_BY_OUR_OWN_LEDGER;
            }
            final long[] srv = look.orNull();
            if (srv != null && srv.length >= 1) {
                serverAnswered = true;
                serverEraForBudget = srv[0];
            }
        } catch (final Throwable t) {
            log.w("MlsConversationRebuild: could not ask the server whether it holds "
                    + MlsConversationKey.forLog(key)
                    + " before rebuilding it", t);
        }
        final MlsReestablishPolicy.Verdict verdict =
                MlsReestablishPolicy.classify(serverAnswered, serverEraForBudget);
        log.i("MlsConversationRebuild: rebuild of " + MlsConversationKey.forLog(key) + " → "
                + verdict + " — "
                + MlsReestablishPolicy.line(verdict, serverEraForBudget));
        // A carry-less group re-establish over a group the server holds forks rather than repairs.
        if (MlsReestablishPolicy.forksAtEraInitial(isGroupKey, verdict,
                MlsWireScan.hasCarry(pack))) {
            log.e("MlsConversationRebuild: NOT rebuilding group " + MlsConversationKey.forLog(key)
                    + " (" + why + ") — "
                    + MlsReestablishPolicy.forkLine(serverEraForBudget) + " No server pack because "
                    + pack.outcome() + ". " + MlsRebuildOutcome.WOULD_FORK_AT_ERA_INITIAL.line());
            return MlsRebuildOutcome.WOULD_FORK_AT_ERA_INITIAL;
        }
        // A group re-create under a contextId the server holds is not applied.
        if (MlsReestablishPolicy.reCreateWouldNotTake(isGroupKey, verdict,
                MlsFreshContextRetry.groupReCreateReusesTheServersContextId(shell))) {
            log.e("MlsConversationRebuild: NOT rebuilding group " + MlsConversationKey.forLog(key)
                    + " (" + why + ") — "
                    + MlsReestablishPolicy.wouldNotTakeLine(serverEraForBudget) + " "
                    + MlsRebuildOutcome.RECREATE_WOULD_NOT_TAKE.line());
                MlsRecoveryPolicy.requestReWelcome(shell, log, rcsGroupId,
                            (peerE164 != null && !peerE164.isEmpty()) ? peerE164
                            : (roster == null || roster.isEmpty() ? null : roster.get(0)),
                    "the server holds this group and a re-create under the contextId it already "
                    + "holds is not applied, so we cannot rebuild our way back in");
            return MlsRebuildOutcome.RECREATE_WOULD_NOT_TAKE;
        }
        // Re-creation and "already paid this episode" are separate questions, never billed twice.
        if (!MlsRecreationEpisode.needsItsOwnCharge(prior)) {
            log.i("MlsConversationRebuild: rebuild of " + MlsConversationKey.forLog(key) + " — "
                    + MlsRecreationEpisode.line(prior));
        }
        if (verdict.chargesEraBudget()
                && MlsRecreationEpisode.needsItsOwnCharge(prior)
                && !shell.peerGuard().allowEraAdvance("rebuild", rcsGroupId, peerE164)) {
            log.e("MlsConversationRebuild: NOT rebuilding " + MlsConversationKey.forLog(key) + " ("
                    + why + ") — the "
                    + "era budget refused it. This rebuild would make every member re-join by "
                    + "Welcome, which is the cost that budget bounds. The allowance refills, so the "
                    + "conversation is deferred rather than abandoned, and the stalled-conversation "
                    + "notification's Try again clears it if a person decides otherwise.");
            return MlsRebuildOutcome.REFUSED_BY_GUARD;
        }
        // After the era budget, which refills faster and so is the cheaper one to waste.
        if (!shell.rebuildLimiter().claim(key)) {
            return MlsRebuildOutcome.RATE_LIMITED;
        }
        log.w("MlsConversationRebuild: AUTOMATIC REBUILD of " + MlsConversationKey.forLog(key)
                + " — " + why
                + ". Dropping BOTH halves of our state and re-establishing. This is the operator's "
                + "deep-forget lever running by itself, which is the whole point.");
        shell.telemetry().count(MlsMetrics.CONVERSATION_REBUILD, 1);

        // Clearing only the engine half would reuse the group id via an era advance.
        final boolean providerCleared = isGroupKey
                ? shell.rpc("mlsForgetGroupConversation").mlsForgetGroupConversation(rcsGroupId)
                : shell.rpc("mlsForgetConversation").mlsForgetConversation(peerE164);
        if (!providerCleared) {
            log.w("MlsConversationRebuild: rebuild of " + MlsConversationKey.forLog(key)
                    + " ABORTED — the provider did "
                    + "not drop its record (unbound, the call failed, or it held none). Clearing "
                    + "only the ENGINE half would re-establish by reusing the group id via an era "
                    + "advance, i.e. back into the state we are trying to leave.");
            return MlsRebuildOutcome.COULD_NOT_ATTEMPT;
        }
        shell.forget(rcsGroupId, peerE164);
        // The carry makes the new era server_era + 1 and inherits 0xF003-0xF006; without it a group
        // is re-created at the initial era, which the server discards.
        final byte[] carry = (serverPack == null) ? null : MlsWireScan.firstPacked(serverPack, 0);
        if (isGroupKey && (carry == null || carry.length == 0)) {
            log.w("MlsConversationRebuild: rebuilding " + MlsConversationKey.forLog(key) + " "
                    + pack.outcome().noCarryLine(MlsTransportTypes.ERA_INITIAL));
        }
        final boolean chargedInThisEpisode =
                verdict.chargesEraBudget() || !MlsRecreationEpisode.needsItsOwnCharge(prior);
        final boolean ready = isGroupKey
                ? MlsGroupEstablish.establishGroup(shell, log, rcsGroupId, roster, carry) >= 0
                : MlsOneToOneGroup.ensureReady(shell, log, key, shell.subId(),
                        java.util.Collections.singletonList(peerE164),
                        /*recreateAlreadyCharged=*/ chargedInThisEpisode,
                        /*stateAlreadyDestroyed=*/ true);
        // Verified by epoch authenticator, not by the create's return value.
        final MlsWelcomeAdmission.ServerState after =
                MlsWelcomeAdmission.serverStateCheck(shell, MlsFetchLedger.Caller.REBUILD,
                        rcsGroupId, peerE164);
        if (after == MlsWelcomeAdmission.ServerState.REFUSED_BY_LEDGER) {
            log.w("MlsConversationRebuild: the rebuild of " + MlsConversationKey.forLog(key)
                    + " completed "
                    + "(ensureReady=" + ready + ") and CANNOT BE VERIFIED — the fetch ledger "
                    + "refused the confirming look. Reported as RAN_BUT_UNVERIFIED, which is neither "
                    + "a repair we can claim nor a failure a person should be told about.");
            return MlsRebuildOutcome.RAN_BUT_UNVERIFIED;
        }
        final boolean ok = ready && after == MlsWelcomeAdmission.ServerState.MATCHES;
        log.i("MlsConversationRebuild: rebuild of " + MlsConversationKey.forLog(key)
                + " → ensureReady=" + ready
                + " serverState=" + after + (ok ? " — REPAIRED" : " — did NOT converge"));
        if (ok) MlsRecoveryPolicy.noteForwardProgress(shell, log, key,
                "automatic rebuild converged");
        return ok ? MlsRebuildOutcome.REPAIRED : MlsRebuildOutcome.NOT_CONVERGED;
    }

    /** The rebuild half of "Try again": clears both bounds, since either would refuse. */
    public static void resetRebuildRateBound(final MlsShellPort shell, final String key) {
        if (key == null) return;
        shell.rebuildLimiter().reset(key);
        shell.peerGuard().resetRebuildEpisode(key);
    }

    /** Test instrument only; nothing in the product may call this. */
    public static void debugResetRebuildAllowance(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164) {
        final String key = MlsConversationKey.canonicalKey(rcsGroupId, peerE164);
        if (key == null) return;
        shell.rebuildLimiter().reset(key);
        shell.peerGuard().resetRebuildEpisode(key);
        log.w("MlsConversationRebuild: TEST INSTRUMENT — cleared the rebuild allowance and "
                + "the episode guard for " + MlsConversationKey.forLog(key)
                + ". Production never does this; the rate limit is "
                + "the thing that keeps one broken conversation from spending hours of allowance.");
    }

    /** Reconciles against the server; {@link MlsDriveLoop} terminates on the outcome. */
    public static MlsHostAction reconcileAction(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164) {
        final Health h = MlsAheadChainCheck.detectHealth(shell, log,
                MlsFetchLedger.Caller.RECONCILE_DRIVE, rcsGroupId, peerE164);
        switch (h) {
            case IN_SYNC_UNVERIFIED:
                log.w("MlsConversationRebuild: reconcile drive found era and epoch matching "
                        + "the server with the DIVERGED test UNRUN (ledger refused) — leaving any "
                        + "stall alert UP and taking no action. An unverified match is not a recovery.");
                return MlsHostAction.none("numbers in sync; the DIVERGED test did not run");
            case IN_SYNC:
                MlsStallAlert.stallCleared(shell, log, rcsGroupId, peerE164,
                        "IN_SYNC with the server");
                return MlsHostAction.none("already in sync with the server");
            case AHEAD:
                // We hold a commit the server did not take; a member's external commit is refused.
                log.e("MlsConversationRebuild: AHEAD of the server — local state is poisoned. "
                        + "Rebuilding rather than asking for a manual wipe; an "
                        + "external commit is not an option (the transport refuses it from a member)");
                final MlsRebuildOutcome ahead = MlsConversationRebuild.rebuildConversation(shell,
                        log, rcsGroupId, peerE164,
                        "AHEAD of the server (poisoned local state); the rebuild discards exactly "
                        + "what is poisoned", MlsRecreationEpisode.PriorCharge.NONE);
                if (ahead.repaired()) {
                    MlsStallAlert.stallCleared(shell, log, rcsGroupId, peerE164,
                            "AHEAD — rebuilt and converged");
                    return MlsHostAction.withStatus(MlsHostAction.Kind.SELF_HEAL_REQUIRED,
                            MlsResultStatus.SUCCESS, MlsGroupSnapshot.NONE,
                            "AHEAD of the server — rebuilt from nothing and converged");
                }
                MlsStallAlert.surfaceIfNobodyElseWill(shell, log, rcsGroupId, peerE164, h, ahead);
                return MlsHostAction.withRedrive(MlsHostAction.Kind.SELF_HEAL_REQUIRED,
                        MlsResultStatus.FAIL_RETRY, MlsGroupSnapshot.NONE,
                        MlsHostAction.Redrive.NOT_IN_THIS_DRIVE,
                        "AHEAD of the server — " + ahead + ": " + ahead.line());
            case DIVERGED:
                log.e("MlsConversationRebuild: DIVERGED — our era and epoch match the server "
                        + "but our epoch authenticator does not, so we are in a DIFFERENT group at "
                        + "the same position. No commit crosses that; rebuilding.");
                final MlsRebuildOutcome diverged = MlsConversationRebuild.rebuildConversation(shell,
                        log, rcsGroupId, peerE164,
                        "DIVERGED — same era/epoch, different epoch authenticator",
                        MlsRecreationEpisode.PriorCharge.NONE);
                if (diverged.repaired()) {
                    MlsStallAlert.stallCleared(shell, log, rcsGroupId, peerE164,
                            "DIVERGED — rebuilt and converged");
                    return MlsHostAction.withStatus(MlsHostAction.Kind.SELF_HEAL_REQUIRED,
                            MlsResultStatus.SUCCESS, MlsGroupSnapshot.NONE,
                            "DIVERGED — rebuilt from nothing and converged");
                }
                MlsStallAlert.surfaceIfNobodyElseWill(shell, log, rcsGroupId, peerE164, h,
                        diverged);
                return MlsHostAction.withRedrive(MlsHostAction.Kind.SELF_HEAL_REQUIRED,
                        MlsResultStatus.FAIL_RETRY, MlsGroupSnapshot.NONE,
                        MlsHostAction.Redrive.NOT_IN_THIS_DRIVE,
                        "DIVERGED — " + diverged + ": " + diverged.line());
            case LOWER_EPOCH_CHAIN_UNKNOWN:
                // Often a commit in flight, so no rebuild; the decrypt-failure detector escalates.
                log.w("MlsConversationRebuild: BEHIND the server — catch-up needs the missed "
                        + "commits, which we do not buffer yet. Re-drivable from a "
                        + "FRESH trigger — this is usually a commit in flight — but NOT inside this "
                        + "drive: this arm changes nothing, so another pass would re-read identical "
                        + "state and spend another GetMlsGroupInfo to do it. The "
                        + "decrypt-failure detector escalates to a rebuild if it persists.");
                return MlsHostAction.withRedrive(MlsHostAction.Kind.SELF_HEAL_REQUIRED,
                        MlsResultStatus.FAIL_RETRY, MlsGroupSnapshot.NONE,
                        MlsHostAction.Redrive.NOT_IN_THIS_DRIVE,
                        "BEHIND the server — catch-up needs the missed commits, which we do not "
                        + "buffer yet; often a commit in flight, so worth re-driving "
                        + "from a later trigger, but this pass changed nothing");
            case ERA_GAP:
            case REJOIN:
            case NOT_FOUND:
                log.w("MlsConversationRebuild: " + h + " — no COMMIT crosses this (that needs "
                        + "a Welcome only the peer or server can send), so rebuilding instead: "
                        + "dropping both halves re-establishes above the server's era");
                final MlsRebuildOutcome stranded = MlsConversationRebuild.rebuildConversation(shell,
                        log, rcsGroupId, peerE164, h + " — stranded in an era no commit can cross",
                        MlsRecreationEpisode.PriorCharge.NONE);
                if (stranded.repaired()) {
                    MlsStallAlert.stallCleared(shell, log, rcsGroupId, peerE164, h
                            + " — rebuilt and converged");
                    return MlsHostAction.withStatus(MlsHostAction.Kind.ERA_ADVANCE_REQUIRED,
                            MlsResultStatus.SUCCESS, MlsGroupSnapshot.NONE,
                            h + " — rebuilt from nothing and converged");
                }
                MlsStallAlert.surfaceIfNobodyElseWill(shell, log, rcsGroupId, peerE164, h,
                        stranded);
                return MlsHostAction.withRedrive(MlsHostAction.Kind.ERA_ADVANCE_REQUIRED,
                        MlsResultStatus.FAIL_RETRY, MlsGroupSnapshot.NONE,
                        MlsHostAction.Redrive.NOT_IN_THIS_DRIVE,
                        h + " — " + stranded + ": " + stranded.line());
            case LOOK_REFUSED:
                log.w("MlsConversationRebuild: health for " + LogMask.number(peerE164)
                        + " is unknown — "
                        + "our own fetch ledger refused the look (the line above names which bound "
                        + "and what this conversation has spent). Nothing came back unreadable, "
                        + "because nothing was asked. NOT asking again in this breath: the remedy "
                        + "for our own bound is to let its window pass, and the retry is scheduled "
                        + "past it rather than into it.");
                return MlsHostAction.withRedrive(MlsHostAction.Kind.NONE, MlsResultStatus.PENDING,
                        MlsGroupSnapshot.NONE, MlsHostAction.Redrive.AFTER_A_COOLDOWN,
                        "health is unknown — our own fetch ledger refused the look, so nothing "
                        + "was asked of the server; waiting for our window is the remedy");
            case UNKNOWN:
            default:
                // PENDING, not NO_OP: we could not find out; the look may be our own throttle.
                log.w("MlsConversationRebuild: health probe for " + LogMask.number(peerE164)
                        + " came back "
                        + "unreadable, so we could not determine health. NOT asking again in this "
                        + "breath: a probe that came back unreadable does not become readable by "
                        + "repeating it, and one of the ways it comes back unreadable is a "
                        + "GetMlsGroupInfo throttle we caused ourselves.");
                return MlsHostAction.withRedrive(MlsHostAction.Kind.NONE, MlsResultStatus.PENDING,
                        MlsGroupSnapshot.NONE, MlsHostAction.Redrive.AFTER_A_COOLDOWN,
                        "could not determine health — the look came back unreadable, which may be a "
                        + "throttle we caused; waiting is the remedy, not asking again");
        }
    }

    /** Debug: clears the durable cooldowns through the same resets Try again uses. */
    public static String debugResetDurableCooldowns(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164) {
        final String key = MlsConversationKey.canonicalKey(rcsGroupId, peerE164);
        if (key == null) return "NO_KEY — nothing to clear";
        MlsConversationRebuild.resetRebuildRateBound(shell, key);
        MlsRecoveryPolicy.resetReestablishCooldown(shell, log, rcsGroupId, peerE164);
        return "cleared the rebuild rate bound, the episode suppressor and the re-establish "
                + "cooldown for " + MlsConversationKey.forLog(key)
                + "; MlsPeerGuard logged what each record held";
    }
}
