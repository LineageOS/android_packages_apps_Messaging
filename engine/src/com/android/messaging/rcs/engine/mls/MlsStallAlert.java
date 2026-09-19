/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Look;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Health;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
/**
 * When to raise and withdraw the stalled-conversation alert. Posting and withdrawing it are the
 * transport's effects ({@code raiseStall}, {@code clearStall}); this class decides when.
 * See docs/mls/health-and-recovery.md.
 */
public final class MlsStallAlert {
    private MlsStallAlert() {}

    /**
     * Raise the alert when a repair was refused rather than failed: a peer guard or the rebuild
     * rate bound ({@link MlsRebuildOutcome#needsAPerson()}). Re-drives cannot lift those, and the
     * alert's Try again resets them.
     */
    public static void surfaceIfNobodyElseWill(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164,
            final Health h, final MlsRebuildOutcome outcome) {
        if (outcome == null || !outcome.needsAPerson()) return;
        final String key = MlsConversationKey.canonicalKey(rcsGroupId, peerE164);
        if (key == null) return;
        log.e("MlsStallAlert: STOPPED repairing " + MlsConversationKey.forLog(key) + " (health=" + h
                + ") — "
                + outcome + ": " + outcome.line()
                + ". Telling the user, because this arm re-drives "
                + "and a re-drive cannot lift what refused us: the guards say STOP AND SURFACE "
                + "IT, and the surfacing is this.");
        shell.raiseStall(key, peerE164, rcsGroupId,
                "health=" + h + " and the repair was stopped rather than failed — " + outcome
                        + ": " + outcome.line());
        final ConvState cs = shell.conv(key);
        synchronized (cs) { cs.stallAlertDown = false; }
    }

    /**
     * Withdraw the alert because the conversation is healthy again. A per-conversation flag makes
     * this once per recovery rather than one binder call per drive; it starts false, so an alert
     * raised before a process restart is still cleared.
     */
    public static void stallCleared(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164, final String why) {
        final String key = MlsConversationKey.canonicalKey(rcsGroupId, peerE164);
        if (key == null) return;
        final ConvState cs = shell.conv(key);
        synchronized (cs) {
            if (cs.stallAlertDown) return;
            cs.stallAlertDown = true;
        }
        log.i("MlsStallAlert: clearing any stall alert on " + MlsConversationKey.forLog(key) + " — "
                + why);
        shell.clearStall(key);
    }

    /** After a user's Try again, re-ask the server whether the conversation is still stalled. */
    public static void refreshStallNotification(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164, final String key) {
        if (key == null || (peerE164 == null && rcsGroupId == null)) return;
        // Its own fetch ration: a person is waiting on this read, so recovery's allowance must not
        // refuse it.
        final Health h = MlsAheadChainCheck.detectHealth(shell, log,
                MlsFetchLedger.Caller.STALL_REFRESH, rcsGroupId, peerE164);
        if (h == Health.LOOK_REFUSED) {
            // We asked nothing, so the alert neither claims success nor failure: leave it as it
            // was.
            log.w("MlsStallAlert: " + MlsConversationKey.forLog(key)
                    + " — the post-retry health check was "
                    + "refused by the fetch ledger, so the stall alert is left UNCHANGED. We did not "
                    + "ask, so we cannot tell this person their retry worked or that it did not.");
            return;
        }
        if (h == Health.IN_SYNC_UNVERIFIED) {
            // Era and epoch match but the divergence test was refused: not enough to claim success.
            log.w("MlsStallAlert: " + MlsConversationKey.forLog(key)
                    + " — the post-retry numbers match but "
                    + "the DIVERGED test was refused by the fetch ledger, so the stall alert is "
                    + "left UNCHANGED. Matching numbers are a POSITION, not an identity: this can "
                    + "be a different group at the same era and epoch, which is the divergence "
                    + "the identity test exists to catch.");
            return;
        }
        if (h == Health.IN_SYNC) {
            log.i("MlsStallAlert: " + MlsConversationKey.forLog(key)
                    + " is IN_SYNC after the user's retry — "
                    + "clearing the stall alert");
            shell.clearStall(key);
            return;
        }
        log.w("MlsStallAlert: " + MlsConversationKey.forLog(key) + " is still " + h
                + " after the user's retry "
                + "— leaving the choice up, because nothing has changed for them");
        shell.raiseStall(key, peerE164, rcsGroupId,
                "still " + h + " after a user-requested retry");
    }

    /**
     * @param exhaustedWhenAsked whether the budget was spent when the escalation asked; used only
     *     when the record cannot be read here
     */
    public static void offerTheStallChoiceOffThread(final MlsConfig cfg, final MlsShellPort shell,
            final MlsLogSink log, final String key, final int attempts,
            final boolean exhaustedWhenAsked) {
        try {
            final String[] parts = MlsConversationKey.splitCanonicalKey(key);
            if (parts == null) return;
            final String rcsGroupId = parts[0];
            // For a group the key has no single peer; the alert and actions use whichever of the
            // pair is set.
            final String peer = parts[1];
            final Health h = MlsAheadChainCheck.detectHealth(shell, log,
                    MlsFetchLedger.Caller.STALL_CHOICE, rcsGroupId, peer);
            // Only an observed ERA_GAP, REJOIN or NOT_FOUND makes a Welcome the sole remedy; a
            // refused look counts as reachable, like UNKNOWN.
            final boolean reachable =
                    h != Health.ERA_GAP && h != Health.REJOIN && h != Health.NOT_FOUND;
            if (h == Health.LOOK_REFUSED) {
                log.w("MlsStallAlert: the stall choice for " + MlsConversationKey.forLog(key)
                        + " is being "
                        + "decided WITHOUT a health reading — the fetch ledger refused the look. "
                        + "Treated as reachable, which is what an UNKNOWN reading already does: "
                        + "only an actual ERA_GAP/REJOIN/NOT_FOUND reading means a Welcome is the only "
                        + "remedy, and nothing was read.");
            }
            if (h == Health.IN_SYNC_UNVERIFIED) {
                // Reachable is already correct here; the log records that the decision rested on
                // half a reading.
                log.w("MlsStallAlert: the stall choice for " + MlsConversationKey.forLog(key)
                        + " is being "
                        + "decided on a PARTIAL health reading — era and epoch matched the server "
                        + "but the DIVERGED test was refused by the fetch ledger. Treated as "
                        + "reachable, which is correct (a Welcome is the only remedy solely on a "
                        + "observed ERA_GAP/REJOIN/NOT_FOUND) — but this conversation could be a "
                        + "different group at the same era and epoch and nothing here would know.");
            }
            // Read again here: the escalation asked from under the lock, and a heal that landed
            // since then has cleared the budget.
            final MlsConversationRecord now = MlsRecordState.recordFor(shell, log, key);
            final boolean exhausted = now == null ? exhaustedWhenAsked
                    : now.selfHealBudget.exhausted(cfg.selfHealRetryLimit,
                            System.currentTimeMillis(), cfg.selfHealWindowMs);
            if (!MlsRecoveryPolicy.needsUserChoice(exhausted, reachable)) {
                if (!exhausted) {
                    log.i("MlsStallAlert: the self-heal budget for "
                            + MlsConversationKey.forLog(key) + " is no longer "
                            + "spent (health " + h + ") — a heal landed after the escalation "
                            + "asked. Nothing to ask the user and nothing to escalate.");
                    return;
                }
                // Budget spent while behind: there is no replay source for missed commits, so
                // nothing else will advance the conversation. First check the server still lists
                // us: if not, self-heal cannot help and the remedy is a Welcome from a member.
                final Group bg = shell.getGroup(key);
                // A refused look is treated like an unreadable roster ("cannot tell"); only FALSE
                // acts.
                final Look<byte[]> rosterLook = (bg == null) ? null
                        : shell.fetchServerPack(MlsFetchLedger.Caller.STALL_CHOICE, rcsGroupId,
                                peer, bg);
                if (rosterLook != null && rosterLook.refused()) {
                    log.w("MlsStallAlert: " + MlsConversationKey.forLog(key)
                            + " is BEHIND and we CANNOT CHECK "
                            + "whether the server still lists us — the fetch ledger refused the "
                            + "roster look. Proceeding as 'cannot tell', which is what an unreadable "
                            + "roster already does: only a definite NOT-LISTED changes the remedy.");
                }
                final Boolean stillListed = (rosterLook == null || rosterLook.refused()) ? null
                        : MlsServerBundle.selfInServerRoster(shell, log, rosterLook.orNull());
                if (Boolean.FALSE.equals(stillListed)) {
                    log.e("MlsStallAlert: " + MlsConversationKey.forLog(key)
                            + " is BEHIND and the SERVER'S "
                            + "ROSTER NO LONGER LISTS US. Self-heal is not the applicable operation "
                            + "(some peers guard exactly this as NonMemberAttemptingToSelfHeal): a "
                            + "member-side repair cannot re-add the member. This needs a Welcome "
                            + "from someone still in the group; raising the stall rather than "
                            + "burning more budget on a repair whose precondition is false.");
                    shell.raiseStall(key, peer, rcsGroupId,
                            "the server's roster no longer lists us — this needs a re-add from a "
                                    + "member, not a self-heal");
                    return;
                }

                // Behind: try adopting the server's GroupInfo by external commit first, since a
                // rebuild creates a fresh group whose epoch authenticator still differs. Other
                // clients recover by roster-matched self-heal instead, and a server may refuse the
                // external commit.
                if (h == Health.LOWER_EPOCH_CHAIN_UNKNOWN
                        && MlsExternalCommitResync.resyncViaExternalCommit(shell, log, rcsGroupId,
                                peer,
                                "health=BEHIND with the self-heal budget spent and no replay source")
                                == MlsProviderRpc.ControlResult.VERDICT_OK) {
                    log.i("MlsStallAlert: " + MlsConversationKey.forLog(key)
                            + " was BEHIND with nothing left "
                            + "to advance it, and REJOINED by resync external commit. This is the "
                            + "catch-up hole closed properly — by adopting the server's state rather "
                            + "than re-creating one of our own.");
                    shell.clearStall(key);
                    return;
                }
                if (h == Health.LOWER_EPOCH_CHAIN_UNKNOWN
                        && MlsConversationRebuild.rebuildConversation(shell, log, rcsGroupId, peer,
                                "health=BEHIND with the self-heal "
                                + "budget spent, and the resync external commit did not take",
                                MlsRecreationEpisode.PriorCharge.NONE)
                                .repaired()) {
                    log.i("MlsStallAlert: " + MlsConversationKey.forLog(key)
                            + " was BEHIND with nothing left "
                            + "to advance it, and REBUILT itself from the server's GroupInfo. This "
                            + "is the catch-up hole being closed by rebuild rather than by replay.");
                    shell.clearStall(key);
                    return;
                }
                log.i("MlsStallAlert: budget exhausted on " + MlsConversationKey.forLog(key)
                        + " but health is "
                        + h
                        + ", which we can still close ourselves — this is backoff, not a stall. "
                        + "NOT asking the user; interrupting them for backoff is how an alert gets "
                        + "trained into noise.");
                return;
            }
            // Try the rebuild before interrupting a person: it crosses ERA_GAP, REJOIN and
            // NOT_FOUND, which no commit can. The alert is for when our own repair did not work.
            final MlsRebuildOutcome lastRung = MlsConversationRebuild.rebuildConversation(shell,
                    log, rcsGroupId, peer, "health=" + h
                    + " and the self-heal budget is spent; rebuilding before asking the user",
                    MlsRecreationEpisode.PriorCharge.NONE);
            if (lastRung.repaired()) {
                log.i("MlsStallAlert: " + MlsConversationKey.forLog(key)
                        + " REPAIRED itself by rebuilding — "
                        + "not raising the stall alert. The user never needed to know.");
                shell.clearStall(key);
                return;
            }
            // Name the rung that failed and how, so a guard refusal is distinguishable from a
            // non-convergence.
            shell.raiseStall(key, peer, rcsGroupId,
                    "self-heal exhausted (attempts=" + attempts + ") and health=" + h
                            + ", which needs a Welcome only the peer or server can send; the last "
                            + "rung was the rebuild and it answered " + lastRung + " — "
                            + lastRung.line());
        } catch (final RuntimeException e) {
            // Never let the alert path break the escalation that called it.
            log.w("MlsStallAlert: could not offer the stall choice for "
                    + MlsConversationKey.forLog(key), e);
        }
    }

    /**
     * Ask the user only when {@link MlsRecoveryPolicy#needsUserChoice} holds: budget exhausted and
     * the awaited moment unreachable. Raising the choice leaves encryption and parked messages
     * unchanged.
     */
    public static void offerTheStallChoice(final MlsConfig cfg, final MlsShellPort shell,
            final MlsLogSink log, final String key, final MlsConversationRecord rec) {
        // Off-thread: the caller holds the conversation lock and deciding needs server I/O, which
        // must not run under it. The off-thread half re-reads the budget, so a conversation that
        // healed in between is not asked about.
        final int attempts = rec == null ? -1 : rec.selfHealBudget.retryCount;
        final boolean exhausted = rec != null && rec.selfHealBudget.exhausted(
                cfg.selfHealRetryLimit, System.currentTimeMillis(), cfg.selfHealWindowMs);
        shell.offThread().run("mls-stall-ask", new Runnable() {
            @Override public void run() { MlsStallAlert.offerTheStallChoiceOffThread(cfg, shell,
                    log, key, attempts, exhausted); }
        });
    }
}
