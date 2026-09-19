/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.Map;
import java.util.List;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
import com.android.messaging.rcs.log.LogMask;
/**
 * The RCC.16 §10 failure-to-decrypt ladder: inbound decrypt failures and the FTD reports they
 * produce, inbound negative-delivery reports about our messages, and when a repeated report stops
 * drawing resends and becomes a group repair. Three bounds apply to one event and are deliberately
 * independent: {@link #ESCALATE_AT}/{@link #WINDOW_MS}, {@link MlsResendBudget}, and
 * {@link #MAX_FTD_ATTEMPTS}. Both {@link ReportClass} rules read the same resend-ledger rows, so a
 * resend drawn by either counts toward both. See docs/mls/health-and-recovery.md.
 */
public final class MlsFtdEscalation {

    private MlsFtdEscalation() {}

    /**
     * Repeat resends to one peer in one conversation, within {@link #WINDOW_MS}, before we stop
     * resending and repair the group: one report is plausibly a lost message, a second about
     * material already resent means the peer's state is the problem. Equal to {@link
     * MlsResendBudget#MAX_PER_WINDOW} today, but not derived from it.
     */
    public static final int ESCALATE_AT = 2;

    /**
     * RCC.16 §10.3's chain cap: stop reporting a message after 5 attempts. Past it RCC.16 hands off
     * to plaintext fallback, which the app does not do automatically. The default of
     * {@link MlsConfig#ftdMaxAttempts}, which production reads.
     */
    public static final int MAX_FTD_ATTEMPTS = 5;

    /**
     * Has this chain passed the cap?
     *
     * @param attempts    this message's attempt number, the one about to be sent included
     * @param maxAttempts the effective cap, {@link MlsConfig#ftdMaxAttempts}
     */
    public static boolean chainExhausted(final int attempts, final int maxAttempts) {
        return attempts > maxAttempts;
    }

    /**
     * The window the repeat count is taken over: one hour, its own value rather than
     * {@link MlsResendBudget#WINDOW_MS}. Long enough that a diverged peer cannot walk the rung down
     * by waiting, short enough that two unrelated failures hours apart are not one broken peer.
     */
    public static final long WINDOW_MS = 60L * 60L * 1000L;

    /** The oldest timestamp still inside the escalation window ending at {@code nowMs}. */
    public static long windowStart(final long nowMs) {
        return nowMs - WINDOW_MS;
    }

    /**
     * Does this report escalate to a group repair?
     *
     * @param repeatResendsInWindow repeat resends this peer has drawn in this conversation inside
     *     {@link #WINDOW_MS}, counting the one this report would draw
     */
    public static boolean escalates(final int repeatResendsInWindow) {
        return repeatResendsInWindow >= ESCALATE_AT;
    }

    /** Which ledger count a report class is judged on. */
    public enum Counting {
        /**
         * {@code MlsResendLedger.repeatResendsToPeerSince}: rows beyond the first per chain. A loop
         * is repeats on one chain, while N distinct messages each get their one resend.
         */
        REPEAT_RESENDS,
        /**
         * {@code MlsResendLedger.resendsToPeerSince}: every row, for an arm that resends without a
         * cause.
         */
        TOTAL_RESENDS,
    }

    /** What happens when a report class reaches its cap. */
    public enum AtTheCap {
        /** Stop resending and repair the group: an era advance, which re-Welcomes every member. */
        ESCALATE_TO_A_GROUP_REPAIR,
        /** Stop, and do nothing else. */
        STOP,
    }

    /**
     * The classes of RCC.16 §7.7.2.2 negative-delivery report that draw a §10.3 resend, and the
     * rule each is judged by. Walked by a host test, so a new class cannot inherit a terminal
     * action by omission.
     */
    public enum ReportClass {
        /**
         * Reason 4, {@code failed-to-decrypt}: repeated failures about resent material are evidence
         * the peer's state is broken, so it escalates.
         */
        DECRYPT_FAILURE(Counting.REPEAT_RESENDS, AtTheCap.ESCALATE_TO_A_GROUP_REPAIR),
        /**
         * {@code <failed/>} with no reason, the common shape from peers. Stops rather than
         * escalating: a report with no cause is not evidence for mutating group state.
         */
        NO_REASON_GIVEN(Counting.TOTAL_RESENDS, AtTheCap.STOP);

        private final Counting mCounting;
        private final AtTheCap mAtTheCap;

        ReportClass(final Counting counting, final AtTheCap atTheCap) {
            mCounting = counting;
            mAtTheCap = atTheCap;
        }

        public Counting counting() {
            return mCounting;
        }

        public AtTheCap atTheCap() {
            return mAtTheCap;
        }

        /** Whether this class may reach {@link MlsFtdEscalation#escalates(int)} at all. */
        public boolean mayEscalate() {
            return mAtTheCap == AtTheCap.ESCALATE_TO_A_GROUP_REPAIR;
        }
    }

    /** One line for the log, naming the rule this report class is being judged by. */
    public static String line(final ReportClass c, final int countInWindow) {
        return c + " is judged on " + c.counting() + " (" + countInWindow + "/"
                + (c.mayEscalate() ? ESCALATE_AT : MlsResendBudget.MAX_PER_WINDOW)
                + ") and at its cap it will " + c.atTheCap();
    }

    public static void reportFtdWithoutHealing(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String fromE164, final String messageId) {
        if (fromE164 == null || messageId == null) return;
        final String key = MlsConversationKey.canonicalKey(rcsGroupId, fromE164);
        if (key == null) return;
        final ConvState cs = shell.conv(key);
        synchronized (cs) { cs.ftdPending.put(messageId, fromE164); }
        log.w("MlsFtdEscalation: EMITTING an FTD for " + MlsMessageId.forLog(messageId) + " to "
                + LogMask.number(fromE164)
                + " WITHOUT self-healing first. This is an instrument, not the §10.1 flow — the "
                + "production path heals and only reports if the failure persists.");
        shell.flushFtdReports(rcsGroupId, fromE164);
    }

    /**
     * Count one more FTD report for {@code messageId} in the durable record, so the §10.3 cap
     * survives a restart.
     *
     * @return the new attempt count, or 1 if the record cannot be read (reporting once beats not at
     *     all)
     */
    public static int bumpFtdResendCount(final MlsShellPort shell, final MlsLogSink log,
            final String key, final String messageId) {
        if (key == null || messageId == null) return 1;
        shell.lock(key);
        try {
            final MlsConversationRecord rec = MlsRecordState.recordFor(shell, log, key);
            if (rec == null) return 1;
            final Integer prev = rec.ftdResendCounts.get(messageId);
            final int next = (prev == null ? 0 : prev) + 1;
            shell.records().put(rec.toBuilder().putFtdResendCount(messageId, next).build());
            return next;
        } finally { shell.unlock(key); }
    }

    /**
     * Handle an application message from a peer that did not decrypt. A ciphertext strictly from
     * the future (RCC.16 §10.8) is parked, not reported; otherwise queue an FTD, self-heal, test
     * convergence against the server, rebuild after a run of non-convergences, and flush the
     * reports.
     */
    public static void onDecryptFailure(final MlsConfig cfg, final MlsShellPort shell,
            final MlsLogSink log, final String rcsGroupId, final String fromE164,
            final String messageId, final byte[] ciphertext, final long eraId) {
        if (fromE164 == null || messageId == null) return;
        final String key = MlsConversationKey.canonicalKey(rcsGroupId, fromE164);
        if (key == null) return;
        // Liveness: a behind member's inbound is undecryptable, so the presence ledger must count
        // failures too, or the advancer election would read "never heard" exactly where it is
        // needed.
        MlsAdvancerElection.noteHeardFrom(shell, key, fromE164);
        if (ciphertext != null && MlsInboundHold.parkFutureCiphertext(shell, log, key, messageId,
                ciphertext, eraId, rcsGroupId, fromE164)) {
            return;
        }
        final ConvState cs = shell.conv(key);
        synchronized (cs) {
            // Queued while a heal is in flight (RCC.16 §10.2): a burst gives one recovery and N
            // reports.
            cs.ftdPending.put(messageId, fromE164);
        }
        final int healed = shell.selfHeal(rcsGroupId, fromE164);
        // An unreadable look is not a failure to converge: the run is left unchanged, since it ends
        // in a rebuild and the fault is often our own throttle. The group-less branch below cannot
        // apply here.
        if (healed == MlsRecoveryPolicy.HEAL_LOOK_UNAVAILABLE) {
            final int run;
            synchronized (cs) { run = cs.consecutiveUnconverged; }
            log.w("MlsFtdEscalation: §10 recovery for " + MlsConversationKey.forLog(key)
                    + " could not READ the "
                    + "server (the self-heal look came back unreadable), so nothing was learned "
                    + "about this conversation. The consecutive-failure run is left UNCHANGED at "
                    + run + " — a look we could not spend is not a heal that did not work, and "
                    + "counting it would spend a rebuild on a fault whose remedy is to wait. "
                    + "The failure is still reported so the sender can resend. Note "
                    + "serverStateCheck() is NOT consulted either: answering it would need the "
                    + "same server we just failed to reach.");
            shell.flushFtdReports(rcsGroupId, fromE164);
            return;
        }
        final MlsWelcomeAdmission.ServerState post =
                MlsWelcomeAdmission.serverStateCheck(shell,
                        MlsFetchLedger.Caller.DECRYPT_FAILURE_CHECK, rcsGroupId, fromE164);
        if (post == MlsWelcomeAdmission.ServerState.REFUSED_BY_LEDGER) {
            // A convergence check our own ledger refused is not a non-convergence; the run is
            // unchanged.
            final int unchangedRun;
            synchronized (cs) { unchangedRun = cs.consecutiveUnconverged; }
            log.w("MlsFtdEscalation: §10 recovery for " + MlsConversationKey.forLog(key)
                    + " could not TEST "
                    + "convergence (heal=" + healed + ") — the fetch ledger refused the look. The "
                    + "consecutive-failure run stays at " + unchangedRun + "; nothing was read, "
                    + "so nothing is concluded. The failure is still reported so the sender can "
                    + "resend.");
            shell.flushFtdReports(rcsGroupId, fromE164);
            return;
        }
        final boolean converged = post == MlsWelcomeAdmission.ServerState.MATCHES;
        if (converged) {
            synchronized (cs) { cs.consecutiveUnconverged = 0; }
            log.i("MlsFtdEscalation: §10 recovery for " + MlsConversationKey.forLog(key)
                    + " CONVERGED (heal="
                    + healed
                    + ") — the messages that failed before this point are still lost to us, "
                    + "so they are reported so the sender can resend them");
        } else {
            final int unconverged;
            synchronized (cs) { unconverged = ++cs.consecutiveUnconverged; }
            log.w("MlsFtdEscalation: §10 recovery for " + MlsConversationKey.forLog(key)
                    + " did NOT converge "
                    + "(heal=" + healed + ", " + unconverged + " in a row) — reporting the failure "
                    + "so a healthy member repairs the group");
            // A run of non-convergences (not one, which is routinely a commit we are early for)
            // rebuilds.
            if (MlsRecoveryPolicy.rebuildAfterUnconverged(unconverged)) {
                log.e("MlsFtdEscalation: " + MlsConversationKey.forLog(key)
                        + " has failed to converge "
                        + unconverged
                        + " recoveries in a row — the §10 ladder cannot fix this from "
                        + "here, so REBUILDING rather than reporting failures indefinitely");
                final MlsRebuildOutcome rebuilt = MlsConversationRebuild.rebuildConversation(shell,
                        log, rcsGroupId, fromE164,
                        unconverged + " consecutive §10 recoveries did not converge",
                        MlsRecreationEpisode.PriorCharge.NONE);
                // Reset either way, so the next decision is made on a fresh run rather than a
                // growing total.
                synchronized (cs) { cs.consecutiveUnconverged = 0; }
                if (!rebuilt.repaired()) {
                    log.i("MlsFtdEscalation: the rebuild of " + MlsConversationKey.forLog(key)
                            + " did not "
                            + "repair it — " + rebuilt + ": " + rebuilt.line() + ". "
                            + MlsRecoveryPolicy.unconvergedRunResetLine());
                }
            }
        }
        // No group at all: we cannot sign an MLS FTD, so ask for a re-Welcome (a group) or
        // re-establish outbound (a 1:1). The plaintext reconciliation receipt is off by default
        // (KEY_PLAINTEXT_RECONCILE_IMDN): peers treat it as a downgrade signal and demote the
        // conversation.
        if (shell.resolveInbound(rcsGroupId, fromE164) == null) {
            log.w("MlsFtdEscalation: hold NO group for " + LogMask.number(fromE164) + " after "
                    + "self-heal — emitting a PLAINTEXT delivery IMDN to RECONCILE (an MLS FTD "
                    + "would only dead-end at CANNOT_PARSE_MESSAGE 16). NOTE: some peers do NOT "
                    + "re-Welcome on this — they DEMOTE the destination and stay demoted.");
            MlsRecoveryPolicy.requestReWelcome(shell, log, rcsGroupId, fromE164,
                    "we hold no group for this conversation and cannot repair it from here: a create "
                    + "would advance us past the server without Welcoming them, external commit is "
                    + "unavailable to a member on this transport (memberExternalCommit=false), and "
                    + "the plaintext receipt meant to provoke recovery demotes some peers "
                    + "instead. Asking is the only non-destructive move we have");
            if (rcsGroupId == null || rcsGroupId.isEmpty()) {
                MlsRecoveryPolicy.reestablishOutbound(shell, log, fromE164);
            }
            if (!cfg.plaintextReconcileImdn) {
                log.w("MlsFtdEscalation: NOT sending the plaintext reconciliation "
                        + "receipt for " + MlsMessageId.forLog(messageId) + " to "
                        + LogMask.number(fromE164)
                        + " — it demotes a real "
                        + "peer (MLS -> Scytale -> plaintext, one rung per receipt) and does not "
                        + "make it re-Welcome. The message stays "
                        + "undelivered, which is accurate. Re-establishment has to be "
                        + "driven outbound from here. Set " + MlsConfig.KEY_PLAINTEXT_RECONCILE_IMDN
                        + "=1 to send it anyway and reproduce the downgrade.");
            } else try {
                // Forced plaintext: the reconciliation only works on the plaintext report channel.
                shell.rpc("reconcileImdn").sendReconciliationReceipt(messageId, fromE164);
            } catch (final Throwable t) {
                log.w("MlsFtdEscalation: reconciliation IMDN failed for "
                        + MlsMessageId.forLog(messageId),
                        t);
            }
            // No MLS FTD for this id: unsigned, it would be rejected.
            synchronized (cs) { cs.ftdPending.remove(messageId); }
            return;
        }
        shell.flushFtdReports(rcsGroupId, fromE164);
    }

    public static boolean resendBudgetSpent(final MlsShellPort shell, final MlsLogSink log,
            final String convKey, final String peerE164, final String messageId, final String why) {
        final int seen = shell.resendLedger().resendsToPeerSince(convKey, peerE164,
                MlsResendBudget.windowStart(System.currentTimeMillis())) + 1;
        if (!MlsResendBudget.spent(seen)) return false;
        log.w("MlsFtdEscalation: " + LogMask.number(peerE164) + " has now reported " + seen
                + " failures (" + why + ") in this conversation — NOT resending "
                + MlsMessageId.forLog(messageId)
                + " again. Resending is demonstrably not fixing it, and without a reason we have no "
                + "evidence for any stronger remedy, so the chain ends here rather than looping.");
        return true;
    }

    /**
     * Drop the resend-ledger rows for one conversation; a debug instrument.
     *
     * @return how many rows were dropped
     */
    public static int forgetResendHistory(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164) {
        final String key = MlsConversationKey.canonicalKey(rcsGroupId, peerE164);
        if (key == null) return 0;
        final int dropped = shell.resendLedger().forgetConversation(key);
        log.w("MlsFtdEscalation: dropped " + dropped + " resend row(s) for "
                + MlsConversationKey.forLog(key)
                + " on operator request. The ladder for this conversation starts from zero again — "
                + "which is the point, and also why this is not something production does.");
        return dropped;
    }

    /**
     * Repair a peer we have concluded is diverged: a whole-group era advance, after checking that
     * our own state matches the server's (self-healing first if not, abandoning if we still differ,
     * deferring if the ledger refused the look). On success the conversation's resend-ledger rows
     * are dropped.
     */
    public static void escalateForDivergedPeer(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164, final String why) {
        // Never advance from a diverged view: it would spread to every member. This check is also
        // the issuance gate for recovery, which is gated on state rather than on a membership
        // delta.
        final MlsWelcomeAdmission.ServerState before = MlsWelcomeAdmission.serverStateCheck(shell, 
                MlsFetchLedger.Caller.DIVERGED_PEER_ESCALATION, rcsGroupId, peerE164);
        if (before == MlsWelcomeAdmission.ServerState.REFUSED_BY_LEDGER) {
            log.w("MlsFtdEscalation: escalation (" + why + ") for " + LogMask.number(peerE164)
                    + " DEFERRED — the fetch ledger refused the look, so we do not know whether our "
                    + "own state matches the server. NOT abandoned and NOT a verdict about this "
                    + "device; the escalation is re-driven from a fresh trigger.");
            return;
        }
        if (before != MlsWelcomeAdmission.ServerState.MATCHES) {
            log.w("MlsFtdEscalation: escalation (" + why + ") for " + LogMask.number(peerE164)
                    + " but OUR OWN state does not match the server (" + before + ") — healing "
                    + "ourselves first; advancing from a diverged view would spread it to the "
                    + "whole group");
            shell.selfHeal(rcsGroupId, peerE164);
            final MlsWelcomeAdmission.ServerState after = MlsWelcomeAdmission.serverStateCheck(
                    shell, MlsFetchLedger.Caller.DIVERGED_PEER_ESCALATION, rcsGroupId, peerE164);
            if (after == MlsWelcomeAdmission.ServerState.REFUSED_BY_LEDGER) {
                log.w("MlsFtdEscalation: escalation for " + LogMask.number(peerE164) + " DEFERRED "
                        + "after the self-heal — the fetch ledger refused the re-check. We are not "
                        + "abandoning: nothing here says we cannot reach the server's state, only "
                        + "that we did not ask again.");
                return;
            }
            if (after != MlsWelcomeAdmission.ServerState.MATCHES) {
                log.e("MlsFtdEscalation: escalation ABANDONED for " + LogMask.number(peerE164)
                        + " — we cannot reach a state the server agrees with (" + after
                        + "), so we "
                        + "are not the member who can repair this group");
                return;
            }
        }
        final int advanced = shell.eraAdvance(rcsGroupId, peerE164);
        if (advanced == MlsEraAdvance.ERA_ADVANCE_LOOK_UNAVAILABLE) {
            log.w("MlsFtdEscalation: era advance NOT ATTEMPTED during escalation ("
                    + why + ") for " + LogMask.number(peerE164)
                    + " — our own fetch ledger refused a look it needs, "
                    + "so nothing was asked of the server and NOTHING here is evidence about the "
                    + "quota or about this peer. The escalation is re-driven from a fresh trigger "
                    + "once the ledger's window has passed.");
            return;
        }
        if (advanced < 0) {
            log.e("MlsFtdEscalation: era advance FAILED during escalation (" + why
                    + ") for " + LogMask.number(peerE164)
                    + ". If this was the era-advance quota, RCC.16's next step "
                    + "is a plaintext downgrade — which we do NOT do silently.");
            return;
        }
        // Every member is re-Welcomed, so the rows that drove the ladder are no longer evidence;
        // leaving them would turn the next first-time failure into another era advance.
        final int dropped = shell.resendLedger().forgetConversation(
                MlsConversationKey.canonicalKey(rcsGroupId, peerE164));
        log.i("MlsFtdEscalation: escalation (" + why + ") for " + LogMask.number(peerE164)
                + " → era advance OK; every member is re-Welcomed into the new era. Released "
                + dropped + " resend row(s); the escalation ladder starts from zero again.");
    }

    /**
     * A peer, or the server, reported that our message failed (RCC.16 §7.7.2.2 client or §7.7.2.1
     * server reasons). The conversation is resolved from the message id, since the receipt carries
     * no group. For {@code failed-to-decrypt} the remedy is "advance to the latest epoch and
     * resend" (RCC.16 §6.2), resending at the current epoch; control failures escalate.
     *
     * @param failureReason the report's reason code; server reasons are offset by
     *     {@code RccNegativeDeliveryImdn.SERVER_BASE}
     */
    public static void onPeerReportedFailure(final MlsConfig cfg, final MlsShellPort shell,
            final MlsLogSink log, final String reportedGroupId, final String peerE164,
            final String messageId, final int failureReason) {
        if (peerE164 == null || messageId == null) return;
        if (!shell.ensureSession()) {
            log.w("MlsFtdEscalation: negative-delivery report for " + MlsMessageId.forLog(messageId)
                    + " from " + LogMask.number(peerE164)
                    + " but no MLS session could be started — dropped");
            return;
        }
        // Recover the group from the message id: a group receipt arrives addressed like a 1:1, and
        // the remedy must not be applied to the 1:1 with the reporter.
        String rcsGroupId = reportedGroupId;
        if (rcsGroupId == null || rcsGroupId.isEmpty()) {
            rcsGroupId = shell.findGroupIdByRcsMessageId(messageId);
            if (rcsGroupId != null) {
                log.i("MlsFtdEscalation: report for " + MlsMessageId.forLog(messageId)
                        + " carried no group; "
                        + "resolved it from the message store → " + rcsGroupId);
            }
        }
        // Fallback: a legacy group wire id ("mls-grp-<rcsGroupId>-<gen>") was never written to the
        // message store, so parse the group out of the id.
        if (rcsGroupId == null || rcsGroupId.isEmpty()) {
            rcsGroupId = MlsMessageId.groupIdFromLegacyWireId(messageId);
            if (rcsGroupId != null) {
                log.i("MlsFtdEscalation: report for " + MlsMessageId.forLog(messageId)
                        + " named no group "
                        + "and is not a row id — recovered the group from the LEGACY group wire id → "
                        + rcsGroupId + ". The sending path should bind the app's rcs_message_id "
                        + "instead; this is a fallback for ids already on the wire.");
            }
        }
        // A group-shaped id we still cannot resolve is refused rather than applied to the 1:1.
        if ((rcsGroupId == null || rcsGroupId.isEmpty())
                && MlsMessageId.isLegacyGroupWireId(messageId)) {
            log.e("MlsFtdEscalation: REFUSING a negative-delivery report for "
                    + MlsMessageId.forLog(messageId) + " from " + LogMask.number(peerE164)
                    + " — the id names a GROUP but we cannot "
                    + "resolve which one, and applying the remedy would repair our 1:1 with them "
                    + "instead. The group stays broken either way; this at least does not damage a "
                    + "conversation that was fine.");
            return;
        }
        final String key = MlsConversationKey.canonicalKey(rcsGroupId, peerE164);
        if (key == null) return;
        // Tripwire: a peer reporter must be a member of the resolved conversation. Not applied to
        // server reasons, which are attributed to us, the sender.
        final boolean serverOriginated =
                RccNegativeDeliveryImdn.ServerReason.fromCode(failureReason) != null;
        if (!serverOriginated
                && !MlsServerBundle.reporterIsAMember(shell, log, rcsGroupId, peerE164, key)) {
            log.e("MlsFtdEscalation: REFUSING a negative-delivery report for "
                    + MlsMessageId.forLog(messageId) + " — " + LogMask.number(peerE164)
                    + " is not a member of " + MlsConversationKey.forLog(key)
                    + ", the "
                    + "conversation that message id resolves to. Either the resolution is wrong or "
                    + "the report is not theirs; applying the remedy would repair the wrong group.");
            return;
        }
        if (serverOriginated) {
            // Logged so the exemption is visible; reporterIsAMember is not called, since our own
            // number is never in the server roster.
            log.i("MlsFtdEscalation: negative-delivery report for " + MlsMessageId.forLog(messageId)
                    + " is SERVER-originated (reason " + failureReason + "), so the membership "
                    + "tripwire does not apply — a server rejection is attributed to us, the sender.");
        }
        if (!serverOriginated) {
            // Liveness: a peer sending failure reports is present. Without this, a designated
            // advancer whose only traffic is failure reports would read NEVER_HEARD, the shortest
            // takeover budget. Whether we may touch the peer at all is MlsPeerGuard's call.
            MlsAdvancerElection.noteHeardFrom(shell, key, peerE164);
        }
        // A server rejection (RCC.16 §7.7.2.1): mostly "your view of the group is wrong".
        final RccNegativeDeliveryImdn.ServerReason srv =
                RccNegativeDeliveryImdn.ServerReason.fromCode(failureReason);
        if (srv != null) {
            // RCC.16 §12.8, before the dispatch: every server reason but an unset one or
            // transient-error invalidates the cached ciphertext (ServerReason.cleansCache), since
            // the server will reject the same bytes again.
            final MlsConversationRecord recForTrace = MlsRecordState.recordFor(shell, log, key);
            log.i(MlsTrace.failedMessageHandled("mls", srv.xmlElement(),
                    recForTrace == null ? "Unknown"
                            : MlsHealthStates.name(recForTrace.healthStatus),
                    "FAIL_NO_RETRY"));
            if (srv.cleansCache()) {
                shell.sealedCache().invalidateForReEncrypt(messageId,
                        "§12.8 — the SERVER rejected it with " + srv.xmlElement()
                                + ", so the cached ciphertext is known-bad");
            }
            switch (srv.disposition()) {
                case SELF_HEAL:
                    // A mismatched-rcs-group-state on a group with end_mls is absorbed here, before
                    // a heal is counted.
                    if (srv == RccNegativeDeliveryImdn.ServerReason.MISMATCHED_RCS_GROUP_STATE
                            && MlsRecordState.hasEndMlsStatus(shell, log, key)) {
                        log.i("Received MISMATCHED_RCS_GROUP_STATE error for downgraded "
                                + "group " + MlsGroupState.groupIdForTrace(shell, key) + ".");
                        return;
                    }
                    log.w("MlsFtdEscalation: the SERVER rejected " + MlsMessageId.forLog(messageId)
                            + " with " + srv.xmlElement() + " — our view of "
                            + MlsConversationKey.forLog(key) + " disagrees "
                            + "with the server's; self-healing rather than resending");
                    shell.selfHeal(rcsGroupId, peerE164);
                    return;
                case RETRYABLE:
                    log.w("MlsFtdEscalation: the SERVER reported a TRANSIENT error for "
                            + MlsMessageId.forLog(messageId)
                            + " — no state repair needed; the send may simply be retried");
                    return;
                case TERMINAL:
                    log.e("MlsFtdEscalation: the SERVER says " + srv.xmlElement()
                            + " for " + MlsMessageId.forLog(messageId)
                            + " — this conversation is no longer an encrypted "
                            + "group. NOT self-healing and NOT resending; recreating or pushing into "
                            + "it would be wrong. Re-establishment is a deliberate action.");
                    return;
                case ESCALATE_TO_ERA: {
                    // The epoch quota is spent; the era allowance is a separate budget, so
                    // escalate.
                    final MlsConversationRecord qrec = MlsRecordState.recordFor(shell, log, key);
                    if (qrec == null) {
                        log.w("MlsFtdEscalation: the SERVER reports "
                                + srv.xmlElement() + " for " + MlsMessageId.forLog(messageId)
                                + " on " + MlsConversationKey.forLog(key)
                                + " — the epoch quota is spent and the era is the escalation, but "
                                + "there is no conversation record to move.");
                        return;
                    }
                    final MlsHealthMachine.Applied qa = MlsHealthMachine.transition(qrec,
                            MlsHealthStates.ERAADVANCEMENTREQUESTED, qrec.moment,
                            /*allowIllegal=*/ false);
                    if (qa.decision.changesState()) {
                        shell.records().put(qa.record);
                        log.w("MlsFtdEscalation: the SERVER reports "
                                + srv.xmlElement() + " for " + MlsMessageId.forLog(messageId)
                                + " on " + MlsConversationKey.forLog(key)
                                + " — the EPOCH advancement quota is spent, so escalating to an ERA "
                                + "advance, which is a separate allowance. This is what some peers do as their "
                                + "remedy for this reason; stopping here would abandon a repair we "
                                + "still have budget for."
                                + MlsInboundHold.inboundNowParked(qa.record));
                    } else {
                        log.i("MlsFtdEscalation: " + MlsConversationKey.forLog(key)
                                + " is out of EPOCH quota but "
                                + "cannot move to EraAdvancementRequested from "
                                + MlsHealthStates.name(qrec.healthStatus) + " — leaving the ladder "
                                + "where it is rather than forcing an illegal transition.");
                    }
                    return;
                }
                case OUT_OF_QUOTA:
                    // The explicit form of the quota; eraQuotaBound reads it. Retrying would only
                    // spend more quota.
                    MlsEraAdvance.noteEraQuotaReported(shell, log, key);
                    log.e("MlsFtdEscalation: the SERVER reports " + srv.xmlElement()
                            + " for " + MlsMessageId.forLog(messageId) + " on "
                            + MlsConversationKey.forLog(key)
                            + " — we are OUT OF QUOTA, which is "
                            + "very likely the explanation for era advances the RPC accepted and the "
                            + "server never applied. NOT retrying: a quota is not cleared by using "
                            + "more of it. RCC.16's next step is a plaintext downgrade, which we do "
                            + "NOT take automatically.");
                    return;
                case OURS_TO_FIX:
                    log.e("MlsFtdEscalation: the SERVER rejected " + MlsMessageId.forLog(messageId)
                            + " as " + srv.xmlElement() + " — malformed on OUR side. Retrying the "
                            + "same bytes cannot help; this needs a code fix, so it is deliberately "
                            + "not retried. Deliberately NOT self-healing: this is a bytes problem, "
                            + "not a state one, and healing would loop on a code bug.");
                    return;
                case REFRESH_IDENTITY:
                    // The receipt carries no MSISDN, so the remedy works out whose credential it
                    // is.
                    MlsCredentialUpdate.expiredCredentialRemedy(cfg, shell, log, key, rcsGroupId,
                            peerE164, messageId);
                    return;
                case COMMIT_PENDING_PROPOSALS:
                    log.w("MlsFtdEscalation: the SERVER reports " + srv.xmlElement()
                            + " for " + MlsMessageId.forLog(messageId) + " on "
                            + MlsConversationKey.forLog(key) + " — committing the cached "
                            + "by-reference proposal(s) rather than healing past them");
                    log.i("MlsFtdEscalation: commitPendingProposals → "
                            + MlsStateChangeGate.commitPendingProposals(shell, log, rcsGroupId,
                                    peerE164));
                    return;
                case NONE:
                default:
                    log.w("MlsFtdEscalation: the SERVER rejected " + MlsMessageId.forLog(messageId)
                            + " with "
                            + srv.xmlElement() + " — classified NONE: there is no safe automatic "
                            + "remedy for it (see ServerReason.disposition()). It is NOT a decrypt "
                            + "failure, so we deliberately do not resend, and NOT a divergence we can "
                            + "confirm, so we deliberately do not self-heal.");
                    return;
            }
        }
        final RccNegativeDeliveryImdn.Reason reason =
                RccNegativeDeliveryImdn.Reason.fromCode(failureReason);
        // Outcome tokens report what the peer already did; a remedy would duplicate it (for
        // commit-failed-then-era-advancement, a second era advance).
        if (reason != null && !reason.isFailure()) {
            // G4's clearing half: only the "processed" outcomes are evidence the peer is
            // transacting with us.
            switch (reason) {
                case COMMIT_PROCESSED_IN_SELF_HEAL:
                case COMMIT_PROCESSED_IN_ENHANCED_SELF_HEAL:
                case PROPOSAL_PROCESSED_IN_SELF_HEAL:
                case PROPOSAL_PROCESSED_IN_ENHANCED_SELF_HEAL:
                    shell.peerGuard().notePeerRecovered(peerE164);
                    break;
                default:
                    break;
            }
            log.i("MlsFtdEscalation: " + LogMask.number(peerE164) + " reports OUTCOME "
                    + reason.xmlElement() + " for " + MlsMessageId.forLog(messageId)
                    + " — informational, the peer has "
                    + "already handled it. No remedy applied (applying one would duplicate its work).");
            return;
        }
        // G4's raising half: an observed negative from the peer itself, not an inference from
        // silence. Server rejections never reach here, and an unmapped code does not count.
        if ((reason != null && reason.isFailure())
                || failureReason == RccNegativeDeliveryImdn.NO_REASON_GIVEN) {
            shell.peerGuard().notePeerFailure(peerE164);
        }
        switch (failureReason) {
            case 3:   // invalid-commit
            case 5:   // commit-in-privatemessage
                log.w("MlsFtdEscalation: " + LogMask.number(peerE164)
                        + " rejected our CONTROL message "
                        + MlsMessageId.forLog(messageId) + " (reason=" + failureReason
                        + ") — escalating to an era "
                        + "advance; a resend cannot fix a peer that will not process our commits");
                MlsFtdEscalation.escalateForDivergedPeer(shell, log, rcsGroupId, peerE164,
                        "control-rejected");
                return;
            case 2:   // invalid-credential
                // RCC.16 §6.2: replace their leaf from a fresh KeyPackage, which an era advance
                // does.
                log.w("MlsFtdEscalation: " + LogMask.number(peerE164)
                        + " reports our credential is "
                        + "invalid for " + MlsMessageId.forLog(messageId)
                        + " — §6.2 wants their leaf replaced from a "
                        + "fresh KeyPackage; escalating (era advance re-Welcomes from fresh KPs)");
                MlsFtdEscalation.escalateForDivergedPeer(shell, log, rcsGroupId, peerE164,
                        "invalid-credential");
                return;
            case 1:   // message-from-non-member
                log.w("MlsFtdEscalation: " + LogMask.number(peerE164) + " says we are NOT a member "
                        + "(message " + MlsMessageId.forLog(messageId)
                        + ") — self-healing our own state rather than "
                        + "resending; if they are right, everything we send is being discarded");
                shell.selfHeal(rcsGroupId, peerE164);
                return;
            case 4:   // failed-to-decrypt — the one that drives resend
                break;
            case RccNegativeDeliveryImdn.NO_REASON_GIVEN:
                // No reason given, the usual shape for a decrypt failure (there is nothing to sign
                // over): the safe half of the reason-4 remedy, a resend at the current epoch, never
                // an escalation. Capped here, because returning early skips the counter below.
                if (MlsFtdEscalation.resendBudgetSpent(shell, log, key, peerE164, messageId,
                        "no-reason-given")) return;
                log.i("MlsFtdEscalation: " + LogMask.number(peerE164) + " reports "
                        + MlsMessageId.forLog(messageId)
                        + " FAILED with no reason given — the wire shape peers send, not an anomaly. "
                        + "Resending at the current epoch, which is the remedy that is safe without "
                        + "knowing the cause. NOT escalating: an era advance on a report we cannot "
                        + "read would mutate a conversation that may be healthy.");
                MlsResend.resendOriginal(shell, log, rcsGroupId, peerE164, messageId);
                return;
            // control-message-failed; also how a group-less peer asks to be re-admitted
            // (requestReWelcome).
            case 13:  // RccNegativeDeliveryImdn.Reason.CONTROL_MESSAGE_FAILED
                log.w("MlsFtdEscalation: " + LogMask.number(peerE164)
                        + " could not process our CONTROL "
                        + "message " + MlsMessageId.forLog(messageId)
                        + " — escalating, same as an explicit rejection. "
                        + "This is also how a peer holding NO group asks to be re-admitted "
                        + "(requestReWelcome), and an era advance re-Welcomes every member.");
                MlsFtdEscalation.escalateForDivergedPeer(shell, log, rcsGroupId, peerE164,
                        "control-message-failed");
                return;
            case 6:   // RccNegativeDeliveryImdn.Reason.RESENT_MESSAGE_FOR_ME_FAILED_TO_DECRYPT
                // Only the resend's target can emit this: its HMAC verified and the inner unwrap
                // failed, which points at how we build the resend.
                log.e("MlsFtdEscalation: " + LogMask.number(peerE164) + " says our RESENT message "
                        + MlsMessageId.forLog(messageId)
                        + " still failed to decrypt. Their HMAC VERIFIED (only the "
                        + "target can emit this reason) and the inner unwrap then failed — so this "
                        + "points at how we BUILD the resend, not at the selector. Capture it.");
                return;
            case 7:   // RccNegativeDeliveryImdn.Reason.COMMIT_PROCESSED_IN_ENHANCED_SELF_HEAL
                log.i("MlsFtdEscalation: " + LogMask.number(peerE164)
                        + " processed our commit during "
                        + "an ENHANCED SELF-HEAL for " + MlsMessageId.forLog(messageId)
                        + " — that is a repair report, "
                        + "not a failure. Taking no action, which is the correct response.");
                return;
            default:
                log.w("MlsFtdEscalation: unmapped negative-delivery reason "
                        + failureReason + " from " + LogMask.number(peerE164) + " for "
                        + MlsMessageId.forLog(messageId)
                        + " — taking NO action. An unrecognised code is not evidence of a decrypt "
                        + "failure, and applying a remedy to a report we cannot read would mutate a "
                        + "conversation that may be perfectly healthy.");
                return;
        }
        // Windowed repeat count from the durable ledger: repeats, not total resends, so N distinct
        // failures each get their resend while a loop on one chain still escalates quickly.
        final int repeats = shell.resendLedger().repeatResendsToPeerSince(key, peerE164,
                MlsFtdEscalation.windowStart(System.currentTimeMillis()));
        if (MlsFtdEscalation.escalates(repeats + 1)) {
            log.w("MlsFtdEscalation: " + LogMask.number(peerE164) + " has drawn " + repeats
                    + " REPEAT resend(s) in the window — resending the same material is not working, "
                    + "so the peer's STATE is the problem; escalating to repair the group");
            MlsFtdEscalation.escalateForDivergedPeer(shell, log, rcsGroupId, peerE164,
                    "repeated-ftd");
            return;
        }
        // "Advance to the latest epoch": rekey only if we are not already there. Rekeying while
        // current would put a behind peer further behind and strand the next member. UNKNOWN, and a
        // ledger refusal (fail-open), rekey.
        final MlsWelcomeAdmission.ServerState ours =
                MlsWelcomeAdmission.serverStateCheck(shell,
                        MlsFetchLedger.Caller.PEER_REPORT_VERIFY, rcsGroupId, peerE164);
        if (ours == MlsWelcomeAdmission.ServerState.REFUSED_BY_LEDGER) {
            log.w("MlsFtdEscalation: " + MlsFetchLedger.describeFailOpen(
                    MlsFetchLedger.Caller.PEER_REPORT_VERIFY,
                    MlsConversationKey.canonicalKey(rcsGroupId, peerE164),
                    "the §6.2 remedy for an FTD from " + LogMask.number(peerE164)
                    + ", taking the rekey arm as "
                    + "though the answer were UNKNOWN"));
        }
        final int rekeyed;
        if (ours == MlsWelcomeAdmission.ServerState.MATCHES) {
            rekeyed = -1;
            log.i("MlsFtdEscalation: FTD from " + LogMask.number(peerE164) + " for "
                    + MlsMessageId.forLog(messageId)
                    + " (repeat-resends " + repeats + "/" + MlsFtdEscalation.ESCALATE_AT
                    + ") — we are ALREADY at the "
                    + "server's latest epoch, so there is nothing to advance to. NOT rekeying: a new "
                    + "epoch would put a behind peer further behind and strand the next member, "
                    + "which is the loop this replaced. Resending at the current epoch, which is the "
                    + "half of §6.2 that helps.");
        } else {
            rekeyed = shell.rekey(rcsGroupId, peerE164);
            log.i("MlsFtdEscalation: FTD from " + LogMask.number(peerE164) + " for "
                    + MlsMessageId.forLog(messageId)
                    + " (repeat-resends " + repeats + "/" + MlsFtdEscalation.ESCALATE_AT
                    + ") — we are " + (ours == MlsWelcomeAdmission.ServerState.DIFFERS
                            ? "NOT at the server's latest epoch"
                            : ours == MlsWelcomeAdmission.ServerState.REFUSED_BY_LEDGER
                                    ? "unable to tell whether we are current because OUR OWN fetch "
                                            + "ledger refused the look (nothing was asked)"
                                    : "unable to tell whether we are current")
                    + ", so advancing is the spec's remedy: key update returned " + rekeyed
                    + "; resending at the CURRENT epoch");
        }
        if (!MlsResend.resendOriginal(shell, log, rcsGroupId, peerE164, messageId)) {
            log.w("MlsFtdEscalation: could not resend " + MlsMessageId.forLog(messageId)
                    + " — see the "
                    + "reason on the lines above. The epoch advance still helps the peer going "
                    + "forward, but this message stays lost for them.");
        }
    }

    public static void flushFtdReports(final MlsConfig cfg, final MlsShellPort shell,
            final MlsLogSink log, final String rcsGroupId, final String flushPeerE164) {
        final String key = MlsConversationKey.canonicalKey(rcsGroupId, flushPeerE164);
        if (key == null) return;
        // Not while a heal is in flight (RCC.16 §10.2 puts the report after recovery). The queue is
        // kept; the heal's completion flushes it.
        if (MlsPendingOperation.healInFlight(shell, log, key)) {
            final int queued;
            final ConvState held = shell.convIfAny(key);
            if (held == null) {
                queued = 0;
            } else {
                synchronized (held) { queued = held.ftdPending.size(); }
            }
            log.i("MlsFtdEscalation: holding " + queued + " §7.7.2.2 report(s) for "
                    + MlsConversationKey.forLog(key)
                    + " — a self-heal is in flight, and §10.2 puts the report AFTER recovery. "
                    + "The queue is kept, not dropped; the heal's completion flushes it.");
            return;
        }
        final java.util.List<String[]> due;
        final ConvState cs = shell.convIfAny(key);
        if (cs == null) return;
        synchronized (cs) {
            if (cs.ftdPending.isEmpty()) return;
            due = new java.util.ArrayList<String[]>(cs.ftdPending.size());
            for (final java.util.Map.Entry<String, String> e : cs.ftdPending.entrySet()) {
                due.add(new String[] { e.getKey(), e.getValue() });
            }
            cs.ftdPending.clear();
        }
        for (final String[] pending : due) {
            final String mid = pending[0];
            // The sender of this message, not the peer that triggered the flush (they differ in a
            // group).
            final String fromE164 = pending[1] != null ? pending[1] : flushPeerE164;
            final int attempts = MlsFtdEscalation.bumpFtdResendCount(shell, log, key, mid);
            if (MlsFtdEscalation.chainExhausted(attempts, cfg.ftdMaxAttempts)) {
                log.w("MlsFtdEscalation: NOT reporting " + MlsMessageId.forLog(mid) + " again — "
                        + attempts
                        + " attempts exceeds the §10.3 maximum of " + cfg.ftdMaxAttempts
                        + ". This "
                        + "message is permanently undecryptable for us; RCC.16 hands off to RCC.71 "
                        + "fallback (plaintext downgrade) at this point, which we do NOT do "
                        + "automatically.");
                continue;
            }
            // Stamp era and epoch authenticator from one group read; without an era, peers default
            // to 1 and drop the report.
            long eraForImdn = -1L;
            String epochAuthForImdn = null;
            // Fail soft: this runs when group state is suspect, and an unstamped report beats none.
            try {
                // resolveInbound, not getGroup: the in-memory map is empty on a fresh process.
                shell.ensureSession();
                final String keyForImdn =
                        MlsGroupState.resolveInbound(shell, log, rcsGroupId, fromE164);
                final Group gForImdn = (keyForImdn == null) ? null : shell.getGroup(keyForImdn);
                if (shell.session() != null && gForImdn != null && gForImdn.groupId != null) {
                    final int eraNowForImdn =
                            MlsAppMessage.eraFrom(shell.session().eraEpoch(gForImdn.groupId));
                    if (eraNowForImdn >= 0) {
                        eraForImdn = eraNowForImdn;
                        final byte[] ea = shell.session().epochAuth(gForImdn.groupId);
                        if (ea != null && ea.length > 0) {
                            epochAuthForImdn = java.util.Base64.getEncoder().encodeToString(ea);
                        }
                    }
                }
            } catch (final Throwable t) {
                log.w("MlsFtdEscalation: could not read the era for the FTD on "
                        + MlsConversationKey.forLog(key)
                        + " — sending the report WITHOUT Era-ID, which some peers default "
                        + "to 1 and probably drop. A report that goes out unstamped still beats one "
                        + "that never goes out.", t);
            }
            // Sign it: the receipt id is minted first so the signature covers it. The reason code
            // is a debug override hook (one-shot); the IMDN header and the signed failure code
            // carry the same value.
            final int reasonCode = MlsPayloadCorruptor.takeFtdReasonOverride(log);
            String negSig = null;
            final String negReceiptId = java.util.UUID.randomUUID().toString();
            try {
                negSig = MlsImdnSigner.signImdn(shell, log, rcsGroupId, fromE164, negReceiptId, mid,
                        /*displayed=*/ false,
                        VerifiableDerivedContent.DELIVERY_FAILED,
                        reasonCode);
            } catch (final Throwable t) {
                log.w("MlsFtdEscalation: could not sign the negative report for "
                        + MlsMessageId.forLog(mid)
                        + " — sending it unsigned, which some peers reject "
                        + "(CANNOT_PARSE_MESSAGE). Unsigned still beats not sending.", t);
            }
            // The same receipt id goes in the envelope, so the receiver's message-id check (RCC.16
            // §7.5.3.1) passes.
            final boolean sent = shell.rpc(
                    "sendMlsNegativeDeliveryImdn").sendMlsNegativeDeliveryImdn(mid, fromE164,
                    rcsGroupId, reasonCode, eraForImdn, epochAuthForImdn, negSig, negReceiptId);
            log.i("MlsFtdEscalation: §7.7.2.2 <failed-to-decrypt> for " + MlsMessageId.forLog(mid)
                    + " → "
                    + LogMask.number(fromE164) + " attempt=" + attempts + "/" + cfg.ftdMaxAttempts
                    + " sent=" + sent);
        }
    }
}
