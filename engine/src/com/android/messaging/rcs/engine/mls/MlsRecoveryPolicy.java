/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;
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
}
