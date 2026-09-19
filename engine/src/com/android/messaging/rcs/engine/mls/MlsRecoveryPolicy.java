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
 * MLS recovery decisions and the shell-driven recovery steps built on them: send-gate bounds, the
 * era-advance tie-break, the non-convergence rebuild threshold, killing a heal and the user's Try
 * again resets. Pure decisions take explicit clocks and inputs. See
 * docs/mls/health-and-recovery.md.
 */
public final class MlsRecoveryPolicy {

    private MlsRecoveryPolicy() {}

    // Thresholds of the recovery ladder. The names are read by the source-scan guards; keep them.

    /**
     * How long a send-gate holds before release; bounds a late ACK. See {@link #sendGateApplies}.
     */
    public static final long GATE_DEADLINE_MS = 90_000L;

    /**
     * Bound on resends parked at one gate; past it escalation, not a longer queue, is the remedy.
     */
    public static final int MAX_GATED_RESENDS = 16;

    /**
     * Consecutive §10 recoveries that may fail to converge before the conversation is rebuilt. One
     * or two can be a race with an in-flight commit; three is a state the ladder cannot leave.
     */
    public static final int UNCONVERGED_BEFORE_REBUILD = 3;

    /** The log line for a resend refused at a full gate; it names {@link #MAX_GATED_RESENDS}. */
    public static String gatedResendRefusalLine(final String conversationKey,
            final String originalMessageId) {
        return MAX_GATED_RESENDS + " resends already parked at the send-gate for "
                + MlsConversationKey.forLog(conversationKey)
                + " — dropping the resend of " + MlsMessageId.forLog(originalMessageId)
                + ". A backlog this deep means "
                + "the peer is not converging; escalation is the remedy, not a deeper queue.";
    }

    /** The clause of the log line that says what resetting the non-convergence run means. */
    public static String unconvergedRunResetLine() {
        return "The failure run is reset, so another " + UNCONVERGED_BEFORE_REBUILD
                + " consecutive failures will ask again rather than asking on every message.";
    }

    /** @param parked how many resends are already held at this conversation's gate */
    public static boolean gatedResendQueueFull(final int parked) {
        return parked >= MAX_GATED_RESENDS;
    }

    /** @param consecutiveUnconverged recoveries that did not converge, back to back */
    public static boolean rebuildAfterUnconverged(final int consecutiveUnconverged) {
        return consecutiveUnconverged >= UNCONVERGED_BEFORE_REBUILD;
    }

    /**
     * Whether a send-gate has outlived its deadline; on expiry the caller sends anyway.
     *
     * @param openedAtMs non-positive if no gate is open
     * @param deadlineMs {@code <= 0} disables the deadline
     */
    public static boolean gateExpired(final long openedAtMs, final long nowMs,
            final long deadlineMs) {
        if (deadlineMs <= 0) return false;      // deadline disabled
        if (openedAtMs <= 0) return false;      // no gate open
        return (nowMs - openedAtMs) > deadlineMs;
    }

    /** {@link #gateExpired(long, long, long)} at {@link #GATE_DEADLINE_MS}; the production form. */
    public static boolean gateExpired(final long openedAtMs, final long nowMs) {
        return gateExpired(openedAtMs, nowMs, GATE_DEADLINE_MS);
    }

    /**
     * Whether a send-gate may be opened on this transport at all. The gate waits for a peer's
     * convergence signal; a transport without one (the peer-to-peer carrier path, which delivers
     * the Welcome directly) could never close it, and the conversation would stall silently.
     */
    public static boolean sendGateApplies(final boolean transportRequiresConvergenceAck) {
        return transportRequiresConvergenceAck;
    }

    /**
     * Whether an external-commit resync is worth attempting. False when the transport refuses an
     * {@code ExternalInit} from an existing member (RFC 9420 §12.4.3.2): the resync deletes the
     * local group before it knows the commit will be accepted, so not trying beats rolling back.
     */
    public static boolean externalCommitResyncApplies(
            final boolean transportAcceptsMemberExternalCommit, final boolean weAreStillAMember) {
        return !weAreStillAMember || transportAcceptsMemberExternalCommit;
    }

    /**
     * On an era gap, whether this side advances the era. Neither RFC 9420 nor RCC.16 defines a
     * tie-break, and two advancing sides duel, so the lower E.164 advances and the other waits for
     * its Welcome. Fails open: an unknown identity advances, since a duel is self-limiting.
     */
    public static boolean weAreEraAdvancer(final String selfE164, final String peerE164) {
        if (selfE164 == null || selfE164.isEmpty()) return true;   // unknown self → advance
        if (peerE164 == null || peerE164.isEmpty()) return true;   // unknown peer → advance
        return selfE164.compareTo(peerE164) <= 0;
    }

    /**
     * Whether a yield to the designated advancer has lasted too long, so we advance anyway.
     *
     * @param waitingSinceMs non-positive if we are not yielding
     * @param yieldMs        {@code <= 0} means yield indefinitely
     */
    public static boolean eraYieldExpired(final long waitingSinceMs, final long nowMs,
            final long yieldMs) {
        if (yieldMs <= 0) return false;         // yield forever
        if (waitingSinceMs <= 0) return false;  // not yielding
        return (nowMs - waitingSinceMs) > yieldMs;
    }

    /**
     * Whether the yield succeeded: the group's moment has changed since we began waiting. Group
     * state, not a wall clock, answers "has the peer advanced". An unknown moment is not movement.
     */
    public static boolean eraYieldSatisfied(final MlsAppMessage.Moment recorded,
            final MlsAppMessage.Moment current) {
        if (recorded == null || current == null) return false;
        return !recorded.equals(current);
    }

    /**
     * Whether we have looked often enough without seeing movement to stop yielding. Counted in
     * observations, not time, so a device asleep for a week wakes having spent nothing.
     */
    public static boolean eraYieldObservationsExhausted(final int observations, final int limit) {
        return limit > 0 && observations >= limit;
    }

    /**
     * Stamps the RCC.16 body header {@code [00 01][00 01][uint32 counter][mls_varint len]} with the
     * generation, which the counter must equal. Non-RCC.16 bodies are returned as is; the input is
     * never mutated.
     */
    public static byte[] stampBodyGeneration(final byte[] framedBody, final int generation) {
        if (framedBody == null || framedBody.length < 8 || generation < 0) return framedBody;
        if (framedBody[0] != 0x00 || framedBody[1] != 0x01
                || framedBody[2] != 0x00 || framedBody[3] != 0x01) {
            return framedBody;   // not an RCC.16-framed body — leave it alone
        }
        final byte[] out = framedBody.clone();
        out[4] = (byte) (generation >>> 24);
        out[5] = (byte) (generation >>> 16);
        out[6] = (byte) (generation >>> 8);
        out[7] = (byte) generation;
        return out;
    }

    /** What a failed external-commit resync must do with the local group it already deleted. */
    public enum ResyncFailureAction {
        /** Put the snapshot back; the conversation continues. */
        RESTORE_SNAPSHOT,
        /** No snapshot: drop the dangling record so the next send re-establishes. */
        DROP_RECORD_AND_REESTABLISH
    }

    /**
     * A resync deletes the local group before it knows the external commit will be accepted, so a
     * failure must leave a usable state: a record pointing at missing group state fails every send
     * and never self-heals, because establish is skipped while the record exists.
     */
    public static ResyncFailureAction onResyncFailure(final boolean haveSnapshot) {
        return haveSnapshot
                ? ResyncFailureAction.RESTORE_SNAPSHOT
                : ResyncFailureAction.DROP_RECORD_AND_REESTABLISH;
    }

    /**
     * Whether recovery has run out of moves and only a person can choose. Both conditions are
     * needed: the budget is spent, and the awaited moment needs a Welcome only a peer or the server
     * can send. The caller asks rather than acts; see
     * {@link MlsHostAction.Kind#USER_ACTION_REQUIRED}.
     */
    public static boolean needsUserChoice(final boolean selfHealBudgetExhausted,
            final boolean awaitedMomentReachable) {
        return selfHealBudgetExhausted && !awaitedMomentReachable;
    }

    /**
     * Ends a failed self-heal ({@code SelfHealKilled}). {@code SelfHealFailed} is outside the §10.8
     * G1 buffering mask, so inbound is decrypted inline again instead of parking forever. See
     * docs/mls/health-and-recovery.md.
     */
    public static void killSelfHeal(final MlsShellPort shell, final MlsLogSink log,
            final String key, final String why) {
        // Only a healing state has an edge to SelfHealFailed; a Healthy group has no buffer to
        // release, and forcing the transition would log a refused edge.
        final MlsConversationRecord rec = MlsRecordState.recordFor(shell, log, key);
        if (rec == null || MlsHealthStates.edge(rec.healthStatus, MlsHealthStates.SELFHEALFAILED)
                == null) {
            log.i("MlsRecoveryPolicy: not killing a self-heal on " + MlsConversationKey.forLog(key)
                    + " — it is "
                    + (rec == null ? "unknown" : MlsHealthStates.name(rec.healthStatus))
                    + ", not a healing state, so there is no heal to kill and no buffer to release ("
                    + why + ")");
            return;
        }
        shell.moveHealth(key, MlsHealthStates.SELFHEALFAILED, "self-heal killed: " + why);
    }

    public static void resetSelfHealBudget(final MlsShellPort shell, final MlsLogSink log,
            final String key) {
        if (key == null) return;
        shell.lock(key);
        try {
            final MlsConversationRecord rec = MlsRecordState.recordFor(shell, log, key);
            if (rec == null) return;
            log.i("MlsRecoveryPolicy: USER-REQUESTED retry on " + MlsConversationKey.forLog(key)
                    + " → self-heal budget reset from " + rec.selfHealBudget.retryCount
                    + ". Encryption state is unchanged; this only buys more repair attempts.");
            shell.records().put(rec.toBuilder()
                    .selfHealBudget(MlsConversationRecord.SelfHealBudget.EMPTY).build());
        } finally { shell.unlock(key); }
    }

    /**
     * Resets the self-heal budget on forward progress (a message processed, an era advanced, a
     * group operation succeeded). Never from a transition hook or at startup: the first means the
     * limit is never reached, the second lets a crash loop buy attempts.
     */
    public static void noteForwardProgress(final MlsShellPort shell, final MlsLogSink log,
            final String key, final String what) {
        if (key == null) return;
        shell.lock(key);
        try {
            final MlsConversationRecord rec = MlsRecordState.recordFor(shell, log, key);
            if (rec == null || rec.selfHealBudget.retryCount == 0) return;
            log.i("MlsRecoveryPolicy: forward progress on " + MlsConversationKey.forLog(key) + " ("
                    + what
                    + ") → self-heal budget reset from " + rec.selfHealBudget.retryCount);
            shell.records().put(rec.toBuilder()
                    .selfHealBudget(MlsConversationRecord.SelfHealBudget.EMPTY).build());
        } finally { shell.unlock(key); }
    }

    /**
     * Persists {@code CannotHealDuringEndMls}, the one write that bypasses the transition table, so
     * the wedge is recorded rather than recomputed on every trigger. Legal only from
     * {@code OngoingEraAdvancement} and {@code EraAdvancementRequested}; elsewhere it logs instead.
     */
    public static void markCannotHealDuringEndMls(final MlsShellPort shell, final MlsLogSink log,
            final String key, final String why) {
        if (key == null) return;
        shell.lock(key);
        try {
            final MlsConversationRecord rec = MlsRecordState.recordFor(shell, log, key);
            if (rec == null) return;
            if (rec.healthStatus == MlsHealthStates.CANNOTHEALDURINGENDMLS) return;
            try {
                shell.records().put(MlsHealthMachine.forceCannotHealDuringEndMls(rec, why));
                log.w("MlsRecoveryPolicy: " + MlsConversationKey.forLog(key)
                        + " → CannotHealDuringEndMls (" + why
                        + ") — persisted, so the wedge survives this call rather than being "
                        + "recomputed from the engine on every trigger");
            } catch (final IllegalArgumentException notDocumented) {
                // Expected from most states; the heal is still refused, without a status write.
                log.i("MlsRecoveryPolicy: " + MlsConversationKey.forLog(key)
                        + " is wedged in end-mls (" + why
                        + ") but the documented escape does not apply from "
                        + MlsHealthStates.name(rec.healthStatus) + " — refusing the heal without a "
                        + "status write");
            }
        } finally { shell.unlock(key); }
    }

    /**
     * {@code selfHeal} return value: the server look-up was refused or unreadable, so nothing was
     * learned. Unlike {@code -1} it does not count toward the non-convergence run, since a rebuild
     * claims a KeyPackage per member for a fault whose remedy is to wait. Still negative, so every
     * {@code healed < 0} check reads it as not healed.
     */
    public static final int HEAL_LOOK_UNAVAILABLE = -2;

    /**
     * The maintenance pass found that its group is not the server's (same era and epoch, different
     * epoch authenticator). Every further input would describe the wrong group, so the pass stops
     * and self-heals. The heavier remedy, a rebuild, stays behind {@code reconcileAction}'s
     * {@code DIVERGED} arm, which re-derives the verdict itself.
     *
     * @return always {@link MlsMaintenancePolicy.Refresh#DIVERGED}
     */
    public static MlsMaintenancePolicy.Refresh maintenanceFoundAFork(final MlsShellPort shell,
            final MlsLogSink log, final String key,
            final String rcsGroupId, final String peerE164, final String cause) {
        log.e("MlsRecoveryPolicy: maintenance (" + cause + ") for " + MlsConversationKey.forLog(key)
                + " — STOPPED. Our era and epoch match the server's and our epoch AUTHENTICATOR "
                + "does not, so this is a DIFFERENT GROUP AT THE SAME POSITION, not a conversation "
                + "that is behind. The era reconciliation above is satisfied "
                + "BY CONSTRUCTION on a same-era fork, which is why this pass used to finish "
                + "normally and record the server's roster onto a group the server has never seen. "
                + "No delta is computed and no era is advanced: a refresh rebuilds THIS group, and "
                + "this group is the wrong one.");
        final int healed = shell.selfHeal(rcsGroupId, peerE164);
        if (healed == HEAL_LOOK_UNAVAILABLE) {
            log.w("MlsRecoveryPolicy: maintenance (" + cause + ") for "
                    + MlsConversationKey.forLog(key)
                    + " — the self-heal could not READ the server, so nothing was learned and "
                    + "nothing was repaired. NOT a failed heal; the next trigger re-runs it.");
        } else if (healed < 0) {
            log.w("MlsRecoveryPolicy: maintenance (" + cause + ") for "
                    + MlsConversationKey.forLog(key)
                    + " — the self-heal looked and could not repair the fork. The heavier remedy is "
                    + "reconcileAction's DIVERGED arm (a rebuild), which MlsRetryWorker and the "
                    + "stall notification reach; this pass deliberately does not escalate there.");
        } else {
            log.i("MlsRecoveryPolicy: maintenance (" + cause + ") for "
                    + MlsConversationKey.forLog(key)
                    + " — self-heal returned " + healed
                    + " for the fork. The NEXT maintenance pass "
                    + "re-asks the identity question; this one claims no repair.");
        }
        return MlsMaintenancePolicy.Refresh.DIVERGED;
    }

    /**
     * Try again: clears the 1:1 re-establish cooldown for the named peer. Only the named peer, not
     * a resolved roster: the conversation needing it holds no group, so a roster lookup would come
     * back empty. A group has no such cooldown and clears nothing.
     */
    public static void resetReestablishCooldown(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164) {
        final String key = MlsConversationKey.canonicalKey(rcsGroupId, peerE164);
        if (key == null) return;
        if (peerE164 == null || peerE164.isEmpty()) {
            log.i("MlsRecoveryPolicy: USER-REQUESTED retry on " + MlsConversationKey.forLog(key)
                    + " names no "
                    + "peer, so no re-establish cooldown was cleared — that bound is on the 1:1 "
                    + "outbound re-establish and a group has none.");
            return;
        }
        shell.peerGuard().resetReestablishCooldown(peerE164);
        log.i("MlsRecoveryPolicy: USER-REQUESTED retry on " + MlsConversationKey.forLog(key)
                + " → re-establish "
                + "cooldown cleared for the named peer.");
    }

    /**
     * Sends a §7.7.2.2 {@code control-message-failed} (reason 13) receipt asking a member with
     * current state to era-advance and re-admit us. Works for a 1:1 too. Only our own clients act
     * on it; other clients route client reasons through their own health status. Reason codes are
     * not a remedy table: several report outcomes, not failures.
     */
    public static void requestReWelcome(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164, final String why) {
        // A 1:1 has no group id; that is the case this is most needed for.
        if (peerE164 == null || peerE164.isEmpty()) return;
        final boolean oneToOne = (rcsGroupId == null || rcsGroupId.isEmpty());
        final String reWelcomeKey = MlsConversationKey.canonicalKey(rcsGroupId, peerE164);
        int era = -1;
        try {
            final Look<long[]> look = shell.lookServerEraEpoch(
                    MlsFetchLedger.Caller.RE_WELCOME_REQUEST, reWelcomeKey, peerE164, rcsGroupId);
            if (look.refused()) {
                // Send unstamped: the stamp helps a peer locate the conversation, but the ask is
                // the only recovery a group-less 1:1 has.
                log.w("MlsRecoveryPolicy: sending the re-Welcome request to "
                        + LogMask.number(peerE164)
                        + " UNSTAMPED — the fetch ledger refused the era look. The ask still goes "
                        + "out: a peer may drop an unstamped report, but not asking at all is the "
                        + "state this mechanism exists to end.");
            } else {
                final long[] srv = look.orNull();
                if (srv != null && srv.length > 0 && srv[0] >= MlsTransportTypes.ERA_INITIAL) era =
                        (int) srv[0];
            }
        } catch (final Throwable t) {
            log.w("MlsRecoveryPolicy: could not read the server's era to stamp the "
                    + "re-Welcome request — sending it unstamped, which a peer may drop", t);
        }
        // The era and epoch authenticator on the outer CPIM name the conversation the report is
        // about, so the server's values are the right ones.
        byte[] epochAuth = null;
        try {
            // A refused look leaves the header absent, never the ask undelivered.
            final Look<byte[]> look = shell.lookServerEpochAuthenticator(
                    MlsFetchLedger.Caller.RE_WELCOME_REQUEST, reWelcomeKey, peerE164, rcsGroupId);
            if (look.refused()) {
                log.w("MlsRecoveryPolicy: the re-Welcome request to " + LogMask.number(peerE164)
                        + " carries NO epoch authenticator — the fetch ledger refused that look "
                        + "too. The header is absent because we did not ask for it, not because the "
                        + "server had none.");
            } else {
                epochAuth = look.orNull();
            }
        } catch (final Throwable t) {
            log.w("MlsRecoveryPolicy: could not fetch the server's epoch authenticator "
                    + "to stamp the re-Welcome request — sending it unstamped, which some peers' "
                    + "engine will refuse to parse", t);
        }
        final String epochAuthB64 = (epochAuth == null || epochAuth.length == 0) ? null
                : java.util.Base64.getEncoder().encodeToString(epochAuth);
        // A 1:1 uses the peer in the id rather than a null group.
        final String mid = "mls-rewelcome-req-" + (oneToOne ? peerE164 : rcsGroupId)
                + "-" + System.currentTimeMillis();
        boolean sent = false;
        try {
            sent = shell.rpc("sendMlsNegativeDeliveryImdn").sendMlsNegativeDeliveryImdn(mid,
                    peerE164, rcsGroupId,
                    RccNegativeDeliveryImdn.Reason.CONTROL_MESSAGE_FAILED.code(),
                    era, epochAuthB64);
        } catch (final Throwable t) {
            log.w("MlsRecoveryPolicy: the re-Welcome request threw", t);
        }
        log.i("MlsRecoveryPolicy: §7.7.2.2 <control-message-failed> RE-WELCOME REQUEST "
                + "for " + (oneToOne ? "the 1:1 with " + LogMask.number(peerE164) : rcsGroupId)
                + " → " + LogMask.number(peerE164)
                + " (era=" + (era < 0 ? "UNSTAMPED" : era)
                + " epochAuth=" + (epochAuthB64 == null ? "NONE — some peers' engines will refuse "
                        + "to parse this" : "server " + epochAuth.length + "B")
                + " sent=" + sent + ") — " + why + ". A member that still holds current state must "
                + "advance the era to re-admit us; we cannot repair this from here.");
    }

    /**
     * Try again: clears the G4 peer-health streak for every member. One tripped member refuses a
     * whole group's rebuild. The roster is resolved locally (recorded membership, then the RCS
     * participant list), since the conversation is unhealthy and has no server pack.
     */
    public static void resetPeerHealth(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164) {
        final String key = MlsConversationKey.canonicalKey(rcsGroupId, peerE164);
        if (key == null) return;
        final List<String> roster = MlsServerBundle.rosterForRebuild(shell, log, rcsGroupId,
                peerE164, key, ServerPack.none(MlsServerPackOutcome.NOT_ASKED_BY_DESIGN));
        if (roster == null || roster.isEmpty()) {
            log.w("MlsRecoveryPolicy: USER-REQUESTED retry on " + MlsConversationKey.forLog(key)
                    + " — no roster "
                    + "could be resolved locally, so no peer-health streak was cleared. If the "
                    + "retry is refused by G4, that is why.");
            return;
        }
        for (final String m : roster) {
            if (m != null && !m.isEmpty()) shell.peerGuard().resetPeerHealth(m);
        }
        log.i("MlsRecoveryPolicy: USER-REQUESTED retry on " + MlsConversationKey.forLog(key)
                + " → peer-health "
                + "streak cleared for " + roster.size() + " peer(s).");
    }

    /**
     * Drives an outbound re-establish when an MLS message arrives for a 1:1 we hold no group for.
     * Runs off-thread because it claims a KeyPackage. Rate-limited by the durable re-establish
     * cooldown in {@link MlsPeerGuard}.
     */
    public static void reestablishOutbound(final MlsShellPort shell, final MlsLogSink log,
            final String peerE164) {
        if (peerE164 == null || peerE164.isEmpty()) return;
        if (!shell.peerGuard().claimReestablishAttempt(peerE164)) return;
        final String key = MlsConversationKey.canonicalKey(/*rcsGroupId=*/ null, peerE164);
        if (key == null) return;
        final String convId = shell.keyToConvId().containsKey(key) ? shell.keyToConvId().get(key)
                : key;
        final int subId = shell.subId();
        shell.offThread().run("mls-reestablish", new Runnable() {
            @Override public void run() {
                try {
                    // Only when the server has no live conversation for this peer: a create
                    // against one it holds does not re-join it. It advances us past the server,
                    // fans no Welcome, and leaves us encrypting into a group nobody else is in.
                    long serverEra = -1L;
                    try {
                        final Look<long[]> look = shell.lookServerEraEpoch(
                                MlsFetchLedger.Caller.REESTABLISH, key, peerE164,
                                /*rcsGroupId=*/ null);
                        if (look.refused()) {
                            // A refused look must not fall through: serverEra = -1 would drive
                            // the create.
                            log.w("MlsRecoveryPolicy: NOT re-establishing with "
                                    + LogMask.number(peerE164)
                                    + " — the fetch ledger refused the look, so we do "
                                    + "not know whether the SERVER still holds this conversation. "
                                    + "Declining rather than assuming it does not: assuming would "
                                    + "drive a create that leaves us in a group nobody else is in. "
                                    + "The server was not asked; the re-establish is re-driven from a "
                                    + "fresh trigger.");
                            return;
                        }
                        final long[] srv = look.orNull();
                        if (srv != null && srv.length >= 1) serverEra = srv[0];
                    } catch (final Throwable t) {
                        log.w("MlsRecoveryPolicy: could not read the server's era for "
                                + LogMask.number(peerE164) + " before re-establishing", t);
                    }
                    if (serverEra >= MlsTransportTypes.ERA_INITIAL) {
                        log.w("MlsRecoveryPolicy: NOT re-establishing with "
                                + LogMask.number(peerE164)
                                + " — the SERVER still holds this conversation at era " + serverEra
                                + ". A create would not re-join it: it would advance us past the "
                                + "server and fan NO Welcome to a peer the server already counts as "
                                + "a member, leaving us holding a group nobody else is in and "
                                + "encrypting undeliverable messages into it ("
                                + "local era/epoch ahead of the server's while the peer stays in sync). "
                                + "Recovery here needs the PEER to re-Welcome us, which a 1:1 has no "
                                + "way to ask for, and that half is still open.");
                        return;
                    }
                    log.w("MlsRecoveryPolicy: an MLS message arrived from "
                            + LogMask.number(peerE164)
                            + " for a 1:1 we hold no usable group for and the SERVER holds none "
                            + "either (era=" + serverEra + ") — DRIVING AN OUTBOUND RE-ESTABLISH. "
                            + "The peer will not do it for us: some peers only re-establish if "
                            + "something asks, and the plaintext receipt that would ask demotes them instead"
                            + ".");
                    final boolean ok = shell.ensureReady(convId, subId,
                            java.util.Collections.singletonList(peerE164));
                    // Report convergence, not the create verdict.
                    final Health h = ok ? MlsAheadChainCheck.detectHealth(shell, log,
                            MlsFetchLedger.Caller.REESTABLISH,
                            /*rcsGroupId=*/ null, peerE164) : null;
                    final String verdict;
                    if (h == Health.IN_SYNC) {
                        verdict = " — converged; the next message from either side should decrypt";
                    } else if (h == Health.LOOK_REFUSED) {
                        verdict = " — UNVERIFIED: the fetch ledger refused the confirming look, so "
                                + "whether this converged is unmeasured. The message stays "
                                + "undelivered either way; the next inbound from them retries.";
                    } else if (h == Health.IN_SYNC_UNVERIFIED) {
                        // Era and epoch converged but the fork test did not run: unmeasured.
                        verdict = " — era and epoch converged, but the DIVERGED test was NOT RUN "
                                + "(the ledger refused the authenticator look), so this could be a "
                                + "different group at the same position. Unmeasured, not converged "
                                + "and not failed.";
                    } else {
                        verdict = " — NOT converged. This message stays undelivered; the next "
                                + "inbound from them retries after the cooldown.";
                    }
                    log.i("MlsRecoveryPolicy: outbound re-establish with "
                            + LogMask.number(peerE164)
                            + " → create=" + ok + " health=" + (h == null ? "n/a" : h) + verdict);
                } catch (final Throwable t) {
                    log.w("MlsRecoveryPolicy: outbound re-establish with "
                            + LogMask.number(peerE164)
                            + " threw", t);
                }
            }
        });
    }
}
