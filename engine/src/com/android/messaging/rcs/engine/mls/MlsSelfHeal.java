/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.List;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Look;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import com.android.messaging.rcs.log.LogMask;
/**
 * The self-heal flow: charge the persisted budget, fetch the missed commits and replay them, or,
 * when replay cannot reach the server's state, era-advance around the server's roster and verify
 * that it took. Engine calls run under the conversation lock; server round trips run outside it.
 * The order of the steps is part of the contract; see docs/mls/health-and-recovery.md.
 */
public final class MlsSelfHeal {
    private MlsSelfHeal() {}

    public static void escalateExhaustedSelfHeal(final MlsConfig cfg, final MlsShellPort shell,
            final MlsLogSink log, final String key, final MlsConversationRecord rec,
            final long now) {
        // Exhaustion is by count only, since the window rolls; the time arm is kept to mirror the
        // two reason codes (6 and 22).
        final boolean byCount = rec.selfHealBudget.retryCount >= cfg.selfHealRetryLimit;
        final MlsHealthMachine.Applied a = MlsHealthMachine.transition(
                rec, MlsHealthStates.ERAADVANCEMENTREQUESTED, rec.moment, /*allowIllegal=*/ false);
        final String why = byCount
                ? "retry limit " + cfg.selfHealRetryLimit + " reached, reason 6"
                : "time limit " + cfg.selfHealWindowMs + "ms reached, reason 22";
        if (a.decision.changesState()) {
            shell.records().put(a.record);
            log.e("MlsSelfHeal: self-heal budget EXHAUSTED for " + MlsConversationKey.forLog(key)
                    + " ("
                    + why + ", attempts=" + rec.selfHealBudget.retryCount + ") → "
                    + MlsHealthStates.name(rec.healthStatus) + " → EraAdvancementRequested. The "
                    + "FailedMessage report is Stage L, so this is visible here and in the record, "
                    + "nowhere else yet." + MlsInboundHold.inboundNowParked(a.record));
            shell.offerTheStallChoice(key, rec);
            shell.terminateRepairIfQuotaBound(key, rec);
            return;
        }
        // The escalation could not land; the cause decides the remedy.
        if (rec.healthStatus == MlsHealthStates.UNKNOWN) {
            // Unknown has no era edge, and a heal records EpochAdvancementRequested before
            // charging, so an exhausted record should not be here.
            log.w("MlsSelfHeal: self-heal budget EXHAUSTED for " + MlsConversationKey.forLog(key)
                    + " ("
                    + why + ", attempts=" + rec.selfHealBudget.retryCount + ") — healing is now "
                    + "BLOCKED for this conversation until real forward progress resets the budget. "
                    + "It could not escalate to EraAdvancementRequested because this record's health "
                    + "is still Unknown, which has 9 outbound edges and no era one. selfHealInner "
                    + "records EpochAdvancementRequested BEFORE charging the budget, so a record "
                    + "that exhausted a heal should not BE here — this is worth investigating.");
            return;
        }
        log.e("MlsSelfHeal: self-heal budget EXHAUSTED for " + MlsConversationKey.forLog(key) + " ("
                + why
                + ") and the escalation " + MlsHealthStates.name(rec.healthStatus)
                + " → EraAdvancementRequested is ILLEGAL per the §5.3 table. That is a real gap in "
                + "the ladder — the conversation is now stuck with no escalation path.");
    }

    /**
     * Charges one self-heal attempt against the conversation's persisted budget, escalating on
     * exhaustion. There is no wall-clock cooldown; the budget is reset only by forward progress
     * ({@link MlsRecoveryPolicy#noteForwardProgress}), never by a transition or at startup.
     *
     * @return true if the caller may proceed; false if the budget is spent and the ladder escalated
     */
    public static boolean chargeSelfHealBudget(final MlsConfig cfg, final MlsShellPort shell,
            final MlsLogSink log, final String key) {
        shell.lock(key);
        try {
            MlsConversationRecord rec = MlsRecordState.recordFor(shell, log, key);
            if (rec == null) return true;          // no record yet: nothing to charge against
            final long now = System.currentTimeMillis();
            // Roll an elapsed window first, or the count would accumulate across windows.
            if (rec.selfHealBudget.windowElapsed(now, cfg.selfHealWindowMs)) {
                log.i("MlsSelfHeal: self-heal window elapsed for " + MlsConversationKey.forLog(key)
                        + " (" + rec.selfHealBudget + ", window " + cfg.selfHealWindowMs
                        + "ms) — starting a NEW window rather than refusing forever");
                rec = rec.toBuilder().selfHealBudget(rec.selfHealBudget.rolled()).build();
                shell.records().put(rec);
            }
            if (rec.selfHealBudget.exhausted(cfg.selfHealRetryLimit, now, cfg.selfHealWindowMs)) {
                MlsSelfHeal.escalateExhaustedSelfHeal(cfg, shell, log, key, rec, now);
                return false;
            }
            shell.records().put(
                    rec.toBuilder().selfHealBudget(rec.selfHealBudget.counted(now)).build());
            return true;
        } finally { shell.unlock(key); }
    }

    public static int selfHealInner(final MlsConfig cfg, final MlsShellPort shell,
            final MlsLogSink log, final String rcsGroupId, final String peerE164,
            final String key) {
        // Group resolution comes before the budget charge, so a conversation with no group spends
        // nothing.
        final Group g = (key == null) ? null : shell.getGroup(key);
        if (g == null || g.groupId == null) {
            log.w("MlsSelfHeal: self-heal has no group for " + rcsGroupId);
            return -1;
        }
        // A group we left is not healed: an era advance re-creates the group with us in it.
        // Checked before the Requested transition and the budget, since no heal was available.
        if (MlsRecordState.weLeft(shell, log, key)) {
            log.w("MlsSelfHeal: NOT self-healing " + MlsConversationKey.forLog(key)
                    + " — WE LEFT this MLS "
                    + "group (RCC.16 §9.4). Healing era-advances, and an era advance re-creates the "
                    + "group with us in it, which would silently undo the departure (INVARIANT "
                    + "ED-1). Rejoining is a separate, explicit action.");
            return 0;
        }
        // A live yield re-looks from local state and returns without charging an attempt or
        // fetching; charging one per look would exhaust the budget before a rank-3 takeover.
        // See MlsSelfHealPass.
        {
            final Integer stillYielding = MlsAdvancerElection.relookLiveYield(cfg, shell, log, key);
            if (stillYielding != null) return stillYielding.intValue();
        }
        // Recorded before the budget charge, so a heal refused for budget leaves evidence.
        shell.moveHealth(key, MlsHealthStates.EPOCHADVANCEMENTREQUESTED, "self-heal requested");
        // Charged before any comparison: a heal that turns out to be a no-op still costs an
        // attempt.
        if (key != null && !MlsSelfHeal.chargeSelfHealBudget(cfg, shell, log, key)) return -1;
        // Engine reads, under the lock.
        final int era;
        final byte[] auth;
        shell.lock(key);
        try {
        // A downgraded group is never healed: an era advance would re-create it without end_mls
        // and re-encrypt a conversation a peer made plaintext (RCC.16 §9.1.1). Two tests: the
        // local 0xF002 catches a downgrade we applied, the status one we requested or that Phoenix
        // has not installed yet.
        if (shell.session().endMlsPresent(g.groupId) || MlsHealthPredicates.isDowngraded(
                MlsRecordState.recordFor(shell, log, key) == null ? MlsHealthStates.UNKNOWN
                : MlsRecordState.recordFor(shell, log, key).healthStatus)) {
            MlsRecoveryPolicy.markCannotHealDuringEndMls(shell, log, key,
                    "local group carries end_mls");
            log.w("Group is ending or has ended MLS on the server. Not self-healing.");
            log.w("MlsSelfHeal: NOT self-healing " + MlsConversationKey.forLog(key)
                    + " — the group carries "
                    + "end_mls (RCC.16 §9.1.1), i.e. it has been DOWNGRADED out of MLS, possibly by "
                    + "a remote peer. Healing would re-encrypt a conversation that is deliberately "
                    + "plaintext. Re-establishing encryption is a separate, explicit action.");
            return 0;
        }
        era = MlsAppMessage.eraFrom(shell.session().eraEpoch(g.groupId));
        auth = shell.session().epochAuth(g.groupId);
        } finally { shell.unlock(key); }
        // The fetch, without the lock.
        final Look<byte[]> healLook = shell.lookMissedCommits(MlsFetchLedger.Caller.SELF_HEAL, key,
                peerE164, rcsGroupId, era, auth);
        if (healLook.refused()) {
            // A look we never made is not a failed heal; -1 would count toward a rebuild.
            log.w("MlsSelfHeal: self-heal for " + MlsConversationKey.forLog(key) + " (era=" + era
                    + ") did "
                    + "NOT fetch — " + healLook.why());
            return MlsRecoveryPolicy.HEAL_LOOK_UNAVAILABLE;
        }
        final byte[] packed = healLook.orNull();
        if (packed == null) {
            // An unreadable look teaches nothing about the group, so it is not a failed heal.
            log.w("MlsSelfHeal: self-heal fetch for " + MlsConversationKey.forLog(key) + " (era="
                    + era
                    + ") — " + MlsFetchBudget.describeUnreadableLook(
                            /*looksSpentBefore=*/ 0, "self-heal " + key));
            return MlsRecoveryPolicy.HEAL_LOOK_UNAVAILABLE;
        }
        // A peer may have ended MLS while we were offline, which the local guard above cannot see.
        // The pack is either a commit list or {GroupInfo, ratchet_tree, roster...}; sniff it.
        // diagnostic: does the server's roster include us?
        MlsServerBundle.selfInServerRoster(shell, log, packed);
        final byte[] head = MlsWireScan.firstPacked(packed, 0);
        final boolean isGroupInfoPack = head != null && head.length >= 4
                && head[0] == 0 && head[1] == 1 && head[2] == 0 && head[3] == 4;   // wire_format 4
        // Only a GroupInfo pack can answer the end_mls question; a commit list cannot vote no.
        if (isGroupInfoPack) {
            final byte[] fetchedGi = head;
            if (MlsStateChangeGate.groupInfoHasEndMls(shell.session(), log, fetchedGi)) {
                log.w("MlsSelfHeal: NOT self-healing " + MlsConversationKey.forLog(key)
                        + " — the SERVER's "
                        + "GroupInfo carries end_mls (0xF002). The group was downgraded out of MLS, "
                        + "possibly while we were offline, so we never applied that commit ourselves. "
                        + "Healing would re-create it and silently re-encrypt a conversation that is "
                        + "deliberately plaintext.");
                // Record DoneEndMls rather than returning a bare 0, so the decline is a state.
                log.i("Self-healing to DoneEndMls because remote group has end_mls: "
                        + MlsGroupState.groupIdForTrace(shell, key));
                shell.moveHealth(key, MlsHealthStates.DONEENDMLS,
                        "the server's GroupInfo carries end_mls — a remote client downgraded this "
                                + "group while we were not looking");
                // The host downgrade: the only arm where a peer's downgrade reaches us with no
                // inbound message.
                shell.downgradeLocally(key, MlsDowngradeReason.RECEIVED_END_MLS_COMMIT,
                        MlsDowngradeReason.eagerFor(MlsDowngradeReason.RECEIVED_END_MLS_COMMIT));
                return 0;
            }
        } else if (head != null) {
            // A commit list is legitimate; say which arm ran.
            log.i("MlsSelfHeal: self-heal fetch for " + MlsConversationKey.forLog(key)
                    + " returned a COMMIT "
                    + "LIST, so the end_mls short-circuit could not be evaluated (it needs the "
                    + "server's GroupInfo). Replaying commits instead.");
        }
        int off = isGroupInfoPack ? packed.length : 0;
        int applied = 0;
        // A commit that would not apply, as opposed to running out of commits.
        boolean stoppedEarly = false;
        int skippedStale = 0;
        // No-op heal: our moment matches the server's, so go straight to Healthy without an
        // Ongoing* state. The budget stays charged.
        final MlsWelcomeAdmission.ServerState moments =
                MlsWelcomeAdmission.serverStateCheck(shell, MlsFetchLedger.Caller.SELF_HEAL,
                        rcsGroupId, peerE164);
        if (moments == MlsWelcomeAdmission.ServerState.REFUSED_BY_LEDGER) {
            // A no-op heal is a claim about the server, and we did not ask it.
            log.w("MlsSelfHeal: self-heal for " + MlsConversationKey.forLog(key)
                    + " cannot run its "
                    + "moments-match check — the fetch ledger refused the look, so we do not know "
                    + "whether this heal is a no-op. Returning HEAL_LOOK_UNAVAILABLE rather than "
                    + "declaring the conversation Healthy on a question we never asked.");
            return MlsRecoveryPolicy.HEAL_LOOK_UNAVAILABLE;
        }
        if (moments == MlsWelcomeAdmission.ServerState.MATCHES) {
            shell.moveHealth(key, MlsHealthStates.HEALTHY,
                    "no-op self-heal — our moment already matches the server's");
            log.i("MlsSelfHeal: self-heal for " + MlsConversationKey.forLog(key)
                    + " is a NO-OP — our moment "
                    + "already matches the server's. Declared Healthy without starting an advance "
                    + "(invariant 99: a no-op never passes through an Ongoing* state).");
            return 0;
        }
        // In flight: a restart must be able to tell a started advance from a requested one.
        shell.moveHealth(key, MlsHealthStates.ONGOINGEPOCHADVANCEMENT,
                "started epoch advancement — replaying missed commits");
        // Replay under the lock again; a commit already held reports PAST_EPOCH and is skipped.
        shell.lock(key);
        try {
        while (off + 4 <= packed.length) {
            final int n = ((packed[off] & 0xFF) << 24) | ((packed[off + 1] & 0xFF) << 16)
                    | ((packed[off + 2] & 0xFF) << 8) | (packed[off + 3] & 0xFF);
            off += 4;
            if (n < 0 || off + n > packed.length) break;
            final byte[] commit = java.util.Arrays.copyOfRange(packed, off, off + n);
            off += n;
            final MlsSession.ProcResult pr = shell.session().processEx(g.groupId, commit);
            // Status 9 = PAST_EPOCH, a commit we already hold.
            if (pr == null || (pr.status != 1 && pr.status != 9)) {
                log.w("MlsSelfHeal: self-heal stopped at commit " + (applied + 1)
                        + " (status=" + (pr == null ? "null" : pr.status)
                        + ") — cannot bridge this "
                        + "gap by replay; the heavier remedy (fresh Welcome) is still required");
                stoppedEarly = true;
                break;
            }
            if (pr.status == 1) applied++;
            // Stale skips are counted apart from applies, so an entirely stale replay is not
            // mistaken for one that could not apply. The engine judges staleness because a
            // commit's era is not readable from the wire bytes.
            if (pr.status == 9) skippedStale++;
        }
        // A partial replay is not a heal: the applied commits are kept and reset the budget, but
        // the record stays unhealthy and the era advance runs.
        if (applied > 0) {
            final int newEra = MlsAppMessage.eraFrom(shell.session().eraEpoch(g.groupId));
            if (newEra >= 0) g.era = newEra;
            g.epochAuth = shell.session().epochAuth(g.groupId);
            shell.putGroup(key, g);
            MlsRecoveryPolicy.noteForwardProgress(shell, log, key, applied
                    + " missed commit(s) replayed");
            if (!stoppedEarly) {
                shell.moveHealth(key, MlsHealthStates.HEALTHY, applied
                        + " missed commit(s) replayed");
                log.i("MlsSelfHeal: SELF-HEALED " + MlsConversationKey.forLog(key) + " — replayed "
                        + applied
                        + " missed commit(s)" + (skippedStale == 0 ? ""
                                : " (and skipped " + skippedStale + " already-held)")
                        + ", now at era=" + g.era + "; strand cleared");
                return applied;
            }
            log.w("MlsSelfHeal: self-heal for " + MlsConversationKey.forLog(key) + " replayed "
                    + applied
                    + " commit(s) and then hit one it could not apply — PARTIAL, not healed. The "
                    + "progress is kept (era=" + g.era + ") and the budget is reset because it IS "
                    + "forward progress, but the group is still behind, so the record is NOT moved "
                    + "to Healthy and the heavier remedy runs. Reporting this as SELF-HEALED is what "
                    + "left conversations diverged with a record claiming otherwise.");
        }
        {
            // The era advance does its own transport I/O, so release the lock; eraAdvance re-takes
            // it.
            shell.unlock(key);
            try {
            if (skippedStale > 0 && applied == 0 && !stoppedEarly) {
                // Entirely stale: we were current and the fetch re-sent history. Not a gap.
                log.i("MlsSelfHeal: self-heal for " + MlsConversationKey.forLog(key) + " — all "
                        + skippedStale + " fetched commit(s) were already held. We were current; "
                        + "nothing to heal, and NOT escalating.");
                shell.moveHealth(key, MlsHealthStates.HEALTHY,
                        "every fetched commit was already held — we were already current");
                return 0;
            }
            // No commits is expected: the group-info fetch does not backfill commits.
            final byte[] serverGi = MlsWireScan.firstPacked(packed, 0);
            final byte[] serverTree = MlsWireScan.firstPacked(packed, 1);
            if (serverGi == null || serverGi.length == 0) {
                log.i("MlsSelfHeal: self-heal for " + MlsConversationKey.forLog(key)
                        + " — no commits and no "
                        + "server GroupInfo; nothing to do");
                return 0;
            }
            // Recover by an era-advance create (new group at era + 1, same RCS group id, every
            // member re-Welcomed), not an external commit: the fetched GroupInfo lacks the
            // external-pub extension.
            final java.util.List<String> roster = new java.util.ArrayList<>();
            for (int i = 2; ; i++) {
                final byte[] m = MlsWireScan.firstPacked(packed, i);
                if (m == null) break;
                final String e164 = new String(m, java.nio.charset.StandardCharsets.UTF_8);
                if (!e164.isEmpty() && !e164.equals(shell.selfE164())) roster.add(e164);
            }
            if (roster.isEmpty()) {
                log.w("MlsSelfHeal: self-heal for " + MlsConversationKey.forLog(key)
                        + " — the fetch returned "
                        + "no other members, so there is nobody to re-Welcome; cannot era-advance");
                return -1;
            }
            // One advancer only, or two members fork the group. The election sorts the roster
            // and bounds the yield by rank; roster stays in pack order because eraAdvance uses
            // roster.get(0) only as the addressing peer.
            final java.util.List<String> electorate = new java.util.ArrayList<>(roster);
            electorate.add(shell.selfE164());
            if (!MlsAdvancerElection.advanceOrYield(cfg, shell, log, key, shell.selfE164(),
                    electorate, "self-heal for " + MlsConversationKey.forLog(key))) {
                return -1;
            }
            log.i("MlsSelfHeal: self-heal for " + MlsConversationKey.forLog(key)
                    + " → SELF-INITIATED ERA "
                    + "ADVANCE, re-creating at era+1 and re-Welcoming " + LogMask.numbers(roster));
            // Hand this heal's own slot to the escalation, or eraAdvance finds it held by us and
            // refuses. Released only when it holds a self-heal's epoch advancement.
            MlsPendingOperation.releaseOwnHealSlotForEscalation(shell, log, key);
            // A death after this point is the most expensive interruption; record that it began.
            shell.moveHealth(key, MlsHealthStates.ONGOINGERAADVANCEMENT,
                    "started era advancement — re-creating at era+1 and re-Welcoming "
                            + LogMask.numbers(roster));
            // The established era-advance path, with the server's GroupInfo so the new era inherits
            // the committed subject and icon; the server refuses the advance otherwise.
            final int newEra = shell.eraAdvance(rcsGroupId, roster.get(0), serverGi);
            if (newEra < 0) {
                // eraAdvance restores its snapshot on every failure path and logs the cause.
                log.e("MlsSelfHeal: self-heal era advance FAILED for "
                        + MlsConversationKey.forLog(key)
                        + " — the conversation is STILL STRANDED. Local state was rolled back to the "
                        + "pre-advance snapshot, so nothing was lost; see the era-advance line above "
                        + "for which of the three failure modes it was.");
                MlsRecoveryPolicy.killSelfHeal(shell, log, key, "era advance failed");
                return -1;
            }
            // Verify against the server: an accepted create is not proof the era moved.
            final Look<long[]> verify = shell.lookServerEraEpoch(MlsFetchLedger.Caller.SELF_HEAL,
                    key, peerE164, rcsGroupId);
            if (verify.refused()) {
                // Neither claim the heal nor kill it: the server was not asked.
                log.w("MlsSelfHeal: self-heal for " + MlsConversationKey.forLog(key)
                        + " advanced to era="
                        + newEra + " and CANNOT VERIFY it against the server — the fetch ledger "
                        + "refused the look. Not claiming the heal and NOT stranding the "
                        + "conversation: the server was not asked, so neither verdict is earned. "
                        + "Returning HEAL_LOOK_UNAVAILABLE so a fresh trigger re-checks.");
                return MlsRecoveryPolicy.HEAL_LOOK_UNAVAILABLE;
            }
            final long[] after = verify.orNull();
            if (after == null || after[0] != newEra) {
                log.e("MlsSelfHeal: self-heal era advance did NOT take — we are at "
                        + "era=" + newEra + " but the server holds era="
                        + (after == null ? "unknown" : after[0])
                        + ". The create was accepted and the "
                        + "era did not move, so we are now MORE diverged, not less. Leaving the "
                        + "conversation marked STRANDED rather than reporting a recovery that did "
                        + "not happen.");
                MlsRecoveryPolicy.killSelfHeal(shell, log, key,
                        "era advance did not take against the server");
                return -1;
            }
            MlsAdvancerElection.clearEraYield(shell, key);
            // Forward progress: an era advanced and the server agrees.
            MlsRecoveryPolicy.noteForwardProgress(shell, log, key, "era advanced to " + newEra
                    + ", server-verified");
            shell.moveHealth(key, MlsHealthStates.HEALTHY, "era advanced to " + newEra
                    + ", server agrees");
            log.i("MlsSelfHeal: SELF-HEALED " + MlsConversationKey.forLog(key)
                    + " by era advance — now at "
                    + "era=" + newEra + ", server agrees; strand cleared");
            return 1;
            } finally { shell.lock(key); } // re-taken so the finally below releases it
        }
        } finally { shell.unlock(key); }
    }

    /**
     * A control message was refused: self-heal if the verdict says we diverged. Only divergence
     * verdicts route here; construction errors and {@code mismatched-rcs-group-state} are not fixed
     * by reconciling. One pending operation per group is the throttle.
     */
    public static void onControlRefused(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164,
            final MlsProviderRpc.ControlResult r, final String what) {
        if (r == null) return;
        final boolean diverged = r.verdict == MlsProviderRpc.ControlResult.VERDICT_ERA_GAP
                || r.verdict == MlsProviderRpc.ControlResult.VERDICT_GROUP_ID_CHANGED;
        if (!diverged) return;
        final String key = MlsConversationKey.canonicalKey(rcsGroupId, peerE164);
        if (key == null) return;
        if (!MlsPendingOperation.claimPendingOp(shell, log, key,
                MlsPendingOperation.Kind.EPOCH_ADVANCEMENT,
                MlsPendingOperation.Origin.PROCESS_MESSAGE_API, what)) {
            log.i("MlsSelfHeal: " + what + " refused (verdict=" + r.verdict
                    + ") but a repair is already in flight for " + MlsConversationKey.forLog(key)
                    + " — not starting a second");
            return;
        }
        log.w("MlsSelfHeal: " + what + " refused with a DIVERGENCE verdict ("
                + r.verdict + ") → self-healing " + MlsConversationKey.forLog(key));
        // Bounded by the drive loop's iteration count rather than a clock, and the loop reads only
        // what each pass returned.
        MlsDriveLoop.Result drive = null;
        try {
            drive = MlsDriveLoop.driveReconcile(shell, log, rcsGroupId, peerE164);
        } finally {
            // The engine module cannot log; emit its lines here, including after a throw.
            if (drive != null) {
                for (final String line : drive.traceLines) log.i(line);
            }
            // Release the slot by outcome so a repeatedly failing repair keeps its history.
            if (drive == null) {
                MlsPendingOperation.failPendingOp(shell, log, key, "the reconcile drive threw");
            } else if (drive.status().mayRetry()) {
                // mayRetry(), not FAIL_RETRY alone: a pending status means we learned nothing and
                // must not release the slot as converged.
                MlsPendingOperation.retryPendingOp(shell, log, key, "reconcile reported "
                        + drive.action.kind);
                // Scheduled only outside a retry flow.
                if (MlsDriveLoop.maySchedule(shell, log, key, "an MLS operation retry")) {
                    // After an unreadable look, start at the first rung past the throttle
                    // cooldown rather than retrying inside it.
                    final boolean throttled =
                            drive.action.redrive == MlsHostAction.Redrive.AFTER_A_COOLDOWN;
                    final int attempt = throttled
                            ? MlsFetchBudget.firstAttemptClearingTheCooldown() : 1;
                    if (throttled) {
                        log.w("MlsSelfHeal: the reconcile of " + MlsConversationKey.forLog(key)
                                + " stopped "
                                + "on a look it could not read, so the retry starts at attempt "
                                + attempt + " (delay "
                                + MlsRetryPolicy.retryDelayMs(attempt) + "ms) rather than at 1 "
                                + "(30000ms) — a GetMlsGroupInfo throttle takes "
                                + MlsFetchBudget.MEASURED_THROTTLE_COOLDOWN_MS + "ms to clear, and "
                                + "retrying inside that window is what causes it.");
                    }
                    shell.scheduleRetry(rcsGroupId, peerE164, attempt);
                }
            } else if (drive.status().isFailure()) {
                MlsPendingOperation.failPendingOp(shell, log, key, "reconcile reported "
                        + drive.action.kind + " — " + drive.action.reason);
            } else {
                MlsPendingOperation.clearPendingOp(shell, log, key);
            }
        }
        // selfHealStatus is the proto number: 1 when a self-heal was driven and failed, else 0.
        final MlsConversationRecord recAfterDrive = MlsRecordState.recordFor(shell, log, key);
        final String healthName = recAfterDrive == null ? "Unknown"
                : MlsHealthStates.name(recAfterDrive.healthStatus);
        log.i(MlsTrace.handleMlsHealthStatus(drive.status().isFailure() ? 1 : 0));
        if (drive.status() == MlsResultStatus.NO_OP) {
            // NO_OP is distinct from SUCCESS; only NO_OP logs "no work needed".
            log.w(MlsTrace.noWorkNeeded(healthName, drive.status().name()));
            // Both lines, as other clients emit them for a no-op heal.
            log.i(MlsTrace.groupInfoMatches(MlsGroupState.groupIdForTrace(shell, key)));
            log.i(MlsTrace.settingHealthyForNoOpSelfHeal(
                    MlsGroupState.groupIdForTrace(shell, key)));
        }
        if (drive.status() == MlsResultStatus.NO_OP || drive.status() == MlsResultStatus.SUCCESS) {
            MlsRecoveryPolicy.noteForwardProgress(shell, log, key, "reconcile converged");
        }
        log.i("MlsSelfHeal: self-heal for " + MlsConversationKey.forLog(key) + " → "
                + (drive.status().isFailure() ? "could not repair automatically" : "IN SYNC"));
    }

    public static void terminateRepairIfQuotaBound(final MlsConfig cfg, final MlsShellPort shell,
            final MlsLogSink log, final String key, final MlsConversationRecord rec) {
        final MlsDowngradeReason reason = MlsDowngradeReason.ERA_ADVANCEMENT_QUOTA_REACHED;
        // Permanent failure: release this conversation's sealed ciphertexts. The pending bodies are
        // a separate store and are kept for a later re-encrypt.
        final int freed = shell.sealedCache().releaseConversation(key,
                "repair budget exhausted against the era-advancement quota — permanent failure");
        if (freed > 0) {
            log.i("MlsSelfHeal: released " + freed + " sealed ciphertext(s) for "
                    + MlsConversationKey.forLog(key)
                    + " on the permanent-failure arm of the retention policy");
        }
        if (!cfg.downgradeOnRepairExhausted) {
            log.w("MlsSelfHeal: " + MlsConversationKey.forLog(key)
                    + " has exhausted the repair budget, and "
                    + "our repair (the era advance) is rate-limited per conversation with no "
                    + "client-visible window — so further attempts inside it fail with an RPC OK and "
                    + "spend a budget nobody can see. Some peers' give-up state here is a DOWNGRADE ("
                    + reason + "). NOT downgrading: " + MlsConfig.KEY_DOWNGRADE_ON_REPAIR_EXHAUSTED
                    + " has been turned OFF. It defaults ON, because the peer's own "
                    + "predicate is ERA_ADVANCEMENT_QUOTA_REACHED -> END_MLS_REQUESTED; this "
                    + "is now an explicit operator choice rather than the absence of evidence. The "
                    + "conversation stays encrypted and parked, and the stall choice above is the "
                    + "user's.");
            return;
        }
        final String[] parts = MlsConversationKey.splitCanonicalKey(key);
        if (parts == null) return;
        // Downgrade on the era quota, not on the self-heal budget alone.
        if (!MlsEraAdvance.eraQuotaBound(shell, log, key, parts[0], parts[1])) {
            log.w("MlsSelfHeal: " + MlsConversationKey.forLog(key)
                    + " has exhausted the SELF-HEAL budget, "
                    + "but the era-advancement quota is NOT established — the server has taken the "
                    + "eras we asked for and has not named the quota. The END_MLS_REQUESTED "
                    + "arm keys on the QUOTA, not on repair exhaustion, so this conversation stays "
                    + "encrypted and keeps the stall choice. Downgrading here would be us being "
                    + "more aggressive than peers are, on a conversation whose fault is elsewhere.");
            return;
        }
        // Try an external-commit resync first; its allowance is separate and it needs no era move.
        final int resync = MlsExternalCommitResync.resyncViaExternalCommit(shell, log, parts[0],
                parts[1],
                "era-advancement quota reached; trying the resync before the END_MLS terminal");
        if (resync == MlsProviderRpc.ControlResult.VERDICT_OK) {
            log.w("MlsSelfHeal: " + MlsConversationKey.forLog(key)
                    + " was rescued by a resync external "
                    + "commit — NOT downgrading. The era quota is still spent, but the conversation "
                    + "is following the server again, which is what the quota was blocking.");
            return;
        }
        log.w("MlsSelfHeal: " + MlsConversationKey.forLog(key)
                + " — repair budget exhausted against a "
                + "rate-limited repair, and the resync external commit did not rescue it (" + resync
                + "); DOWNGRADING out of MLS (" + reason
                + "), which is the terminal some peers use for this "
                + "(ERA_ADVANCEMENT_QUOTA_REACHED -> END_MLS_REQUESTED). The conversation "
                + "stops being encrypted and stops consuming the limit; re-upgrade is the ordinary "
                + "path back.");
        final int r =
                MlsDowngradeFlow.endMls(shell, log, parts[0], parts[1], /*resume=*/ false, reason);
        log.i("MlsSelfHeal: terminal downgrade for " + MlsConversationKey.forLog(key) + " returned "
                + r);
    }

    public static int selfHeal(final MlsConfig cfg, final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164) {
        if (!shell.ensureSession()) return -1;
        final String key = MlsConversationKey.canonicalKey(rcsGroupId, peerE164);
        // Take the single pending slot, not just read it, so a second heal sees it held.
        if (MlsPendingOperation.healAlreadyPending(shell, log, key)) return 0;
        if (key != null && !MlsPendingOperation.claimPendingOp(shell, log, key,
                MlsPendingOperation.Kind.EPOCH_ADVANCEMENT,
                MlsPendingOperation.Origin.OTHER, "self-heal")) {
            log.i("MlsSelfHeal: self-heal for " + MlsConversationKey.forLog(key)
                    + " could not take the "
                    + "pending slot. No-op, not a failure. (claimPendingOp has just logged WHY — a "
                    + "held slot and a missing record are different problems with different "
                    + "remedies, and this line used to assert the first for both.)");
            return 0;
        }
        try {
            return MlsSelfHeal.selfHealInner(cfg, shell, log, rcsGroupId, peerE164, key);
        } finally {
            // Release on every exit, including throws.
            MlsPendingOperation.clearPendingOp(shell, log, key);
        }
    }
}
