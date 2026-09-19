/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.messaging.rcs.engine.mls;

/**
 * The MLS recovery DECISIONS, extracted as pure functions with no Android and no engine
 * dependencies so they can be unit-tested on the host JVM.
 *
 * <p>Why this class exists: every one of these rules only fires in a failure the fleet does not
 * produce on demand — convergence that never arrives, an external commit that fails to build, a peer
 * that advances its era while we are the higher-sorting side. Testing them on-device would mean
 * shipping fault-injection hooks in the send path, which is a liability. Extracting the decision
 * (not the plumbing) keeps the production call sites honest — {@code MlsMessageTransport} and
 * {@code MlsResyncManager} call straight into these methods, so the tests exercise the real logic
 * rather than a copy of it.
 *
 * <p>All methods are total: they take explicit clocks/inputs and never read system state.
 */
public final class MlsRecoveryPolicy {

    private MlsRecoveryPolicy() {}

    // ---- the ladder's thresholds ----------------------------------------------------------------
    //
    // These three were `private static final` in MlsProviderTransport, where no host test could
    // reach them and where the comparison that spends each one sat inline in a method that also
    // performed an Android effect. The NUMBER moving alone would have been bookkeeping: what moves
    // with it is the comparison, as a predicate below, so the transport asks a question instead of
    // doing the arithmetic. Their names are unchanged deliberately — tools/mls/transport-classify.py
    // carries them in POLICY_CONSTANTS and both DoD-1 and DoD-3 read that list, so a rename here
    // would silently drop them out of the guards that watch them.

    /**
     * How long a send-gate may hold outbound app messages before it is released anyway.
     *
     * <p>90 seconds. The deadline is a safety net for a convergence ACK that is LATE; the question
     * of whether such an ACK exists at all is {@link #sendGateApplies}, and a caller must ask that
     * one first. On expiry we release and send: the worst case is a generation the peer ignores,
     * which is strictly better than a conversation that never sends again.
     */
    public static final long GATE_DEADLINE_MS = 90_000L;

    /**
     * Bound on resends parked at ONE gate.
     *
     * <p>A gate lives at most {@link #GATE_DEADLINE_MS}, so this is reached only by a peer reporting
     * failures far faster than they can be remedied — at which point the escalation ladder, not a
     * longer queue, is the answer.
     */
    public static final int MAX_GATED_RESENDS = 16;

    /**
     * How many §10 recoveries may fail to converge in a row before we stop reporting and REBUILD
     *.
     *
     * <p>Three, because one is routinely a commit we are a moment early for and two can still be the
     * same commit arriving twice. Three consecutive is not a race — it is a state the ladder cannot
     * leave.
     */
    public static final int UNCONVERGED_BEFORE_REBUILD = 3;

    /**
     * The line a refused resend logs — the policy's own explanation of its own cap.
     *
     * <p>Here rather than at the call site for {@link MlsFetchLedger#describeRefusal}'s reason: the
     * sentence names the threshold, so leaving it in the transport would have left the number there
     * too, spelled into a string where DoD-1 cannot see it and no host test can read it.
     */
    public static String gatedResendRefusalLine(final String conversationKey,
            final String originalMessageId) {
        return MAX_GATED_RESENDS + " resends already parked at the send-gate for " + conversationKey
                + " — dropping the resend of " + originalMessageId + ". A backlog this deep means "
                + "the peer is not converging; escalation is the remedy, not a deeper queue.";
    }

    /**
     * The line that says what resetting the non-convergence run means for the next one.
     *
     * <p>Same reason as above: the sentence is about {@link #UNCONVERGED_BEFORE_REBUILD}, so it
     * belongs where that number is declared. The caller supplies the conversation and the outcome;
     * this is only the clause the threshold owns.
     */
    public static String unconvergedRunResetLine() {
        return "The failure run is reset, so another " + UNCONVERGED_BEFORE_REBUILD
                + " consecutive failures will ask again rather than asking on every message.";
    }

    /**
     * Is the parked-resend queue at this gate full?
     *
     * @param parked how many resends are already held at this conversation's gate
     */
    public static boolean gatedResendQueueFull(final int parked) {
        return parked >= MAX_GATED_RESENDS;
    }

    /**
     * Has this conversation failed to converge often enough in a row to be REBUILT?
     *
     * @param consecutiveUnconverged recoveries that did not converge, back-to-back
     */
    public static boolean rebuildAfterUnconverged(final int consecutiveUnconverged) {
        return consecutiveUnconverged >= UNCONVERGED_BEFORE_REBUILD;
    }

    /**
     * Has a send-gate outlived its deadline?
     *
     * <p>A gate holds outbound app messages until the peer converges. With no deadline that wait is
     * unbounded, so a convergence signal that never arrives (peer gone, ACK dropped, or a gate that
     * outlived the group it was opened for) silently stops the conversation forever. On expiry the
     * caller releases and sends anyway: the worst case is a generation the peer ignores, which is
     * strictly better than messages that are never sent.
     *
     * @param openedAtMs when the gate opened, or a non-positive value if no gate is open
     * @param nowMs      current wall clock
     * @param deadlineMs how long a gate may hold; {@code <= 0} disables the deadline entirely
     */
    public static boolean gateExpired(final long openedAtMs, final long nowMs, final long deadlineMs) {
        if (deadlineMs <= 0) return false;      // deadline disabled
        if (openedAtMs <= 0) return false;      // no gate open
        return (nowMs - openedAtMs) > deadlineMs;
    }

    /**
     * Has a send-gate outlived {@link #GATE_DEADLINE_MS}? The shipping form.
     *
     * <p>The three-argument form above stays, and the tests use it to walk the boundary at windows
     * a test chooses. Production takes this one so the deadline is declared once, next to the rule
     * that spends it, rather than passed in from a class no host test can reach.
     */
    public static boolean gateExpired(final long openedAtMs, final long nowMs) {
        return gateExpired(openedAtMs, nowMs, GATE_DEADLINE_MS);
    }

    /**
     * May a send-gate be opened on this transport at all?
     *
     * <p>The gate exists for one purpose: hold outbound app messages until the peer signals it has
     * converged after a commit. That signal is transport-specific — on Tachyon it is a 66-byte control
     * ACK. A transport with <b>no</b> convergence signal has nothing that could ever close the gate,
     * so opening one would stall the conversation forever waiting for an event that cannot arrive.
     *
     * <p>This is the concrete reason the transport profile exists. Our two real transports differ
     * exactly here: Tachyon is server-mediated and acknowledges convergence; the carrier MSRP path is
     * peer-to-peer and delivers the Welcome directly, with nothing to await. Carrying Tachyon's
     * assumption into the carrier path would produce a permanent, silent send stall — a hang with no
     * error, which is this subsystem's worst failure shape.
     *
     * <p>Note the deadline in {@link #gateExpired} is a safety net for a signal that is <i>late</i>;
     * this is the prior question of whether the signal exists at all. A caller must ask this first.
     *
     * @param transportRequiresConvergenceAck from the transport's declared profile
     */
    public static boolean sendGateApplies(final boolean transportRequiresConvergenceAck) {
        return transportRequiresConvergenceAck;
    }

    /**
     * Is an external-commit resync worth attempting on this transport?
     *
     * <p>False when the transport refuses an {@code ExternalInit} from an existing member — which is
     * both RFC 9420 §12.4.3.2's rule and, device-proven, Tachyon's actual behaviour. Attempting it
     * anyway costs a round trip and, worse, our resync path DELETES the local group before it knows
     * the commit will be accepted (the BUG-04 shape). Not trying is strictly better than trying and
     * rolling back.
     *
     * @param transportAcceptsMemberExternalCommit from the transport's declared profile
     * @param weAreStillAMember whether we currently hold membership in the group
     */
    public static boolean externalCommitResyncApplies(
            final boolean transportAcceptsMemberExternalCommit, final boolean weAreStillAMember) {
        return !weAreStillAMember || transportAcceptsMemberExternalCommit;
    }

    /**
     * On an ERA_GAP, are WE the side that should advance the era?
     *
     * <p>If both sides answer an era gap by creating the next era they duel: we mint N+1, the peer
     * sees a newer era and mints N+2, and so on. Neither RFC 9420 nor RCC.16 defines a tie-break —
     * RFC 9420 §14 explicitly delegates conflict resolution to the application and supplies none, and
     * Tachyon only arbitrates after the fact via {@code INCORRECT_ERA}. So we impose one: the
     * numerically-lower E.164 advances, the higher one waits for the advancer's Welcome. One
     * advancer, one joiner.
     *
     * <p>Fails OPEN: if our own identity is unknown we advance, because a conversation that never
     * recovers is worse than a possible duel (and a duel is self-limiting — whoever's create lands
     * first wins, and the loser then sees IN_SYNC).
     */
    public static boolean weAreEraAdvancer(final String selfE164, final String peerE164) {
        if (selfE164 == null || selfE164.isEmpty()) return true;   // unknown self → advance
        if (peerE164 == null || peerE164.isEmpty()) return true;   // unknown peer → advance
        return selfE164.compareTo(peerE164) <= 0;
    }

    /**
     * We yielded the era advance to the peer — has it taken too long to act?
     *
     * <p>Yielding must not deadlock. If the designated advancer never advances (it crashed, lost the
     * conversation, or never noticed the gap) the yielding side would wait forever and the
     * conversation would be permanently one-way. After this bound we advance anyway.
     *
     * @param waitingSinceMs when we started yielding, or non-positive if we are not yielding
     * @param yieldMs        how long to yield; {@code <= 0} means yield indefinitely
     */
    public static boolean eraYieldExpired(final long waitingSinceMs, final long nowMs,
            final long yieldMs) {
        if (yieldMs <= 0) return false;         // yield forever
        if (waitingSinceMs <= 0) return false;  // not yielding
        return (nowMs - waitingSinceMs) > yieldMs;
    }

    /**
     * Did the yield SUCCEED — i.e. has the group moved since we started waiting? (Rework 5.3.)
     *
     * <p>This replaces the wall clock as the <em>success</em> test, and the substitution is the
     * point of item 5.3. "Has the peer advanced the era?" is a question about GROUP STATE, and a
     * stopwatch cannot answer it: two devices' clocks disagree, a sleeping device wakes with its
     * deadline long past, and 120 seconds elapsing says nothing whatever about whether the peer
     * acted. The moment says exactly that and nothing else.
     *
     * <p>An unknown moment on either side is NOT movement — an upgrade that introduced the field
     * must not read as "the peer advanced".
     *
     * @param recorded the moment when we began yielding
     * @param current  the group's moment now
     */
    public static boolean eraYieldSatisfied(final MlsAppMessage.Moment recorded,
            final MlsAppMessage.Moment current) {
        if (recorded == null || current == null) return false;
        return !recorded.equals(current);
    }

    /**
     * Have we waited long enough to stop yielding and advance anyway? (Rework 5.3.)
     *
     * <p>Counted in OBSERVATIONS — times we have been asked and found the group unmoved — rather
     * than in milliseconds. Yielding must not deadlock: if the designated advancer never acts (it
     * crashed, lost the conversation, or never noticed the gap) the yielding side would wait forever
     * and the conversation would be permanently one-way.
     *
     * <p>A bound is genuinely required here and the moment alone cannot supply one: the moment tells
     * us whether the peer acted, never how long we have waited. So the bound is a count, which is
     * the §8.7-compatible shape — it advances only when we actually look, so a device asleep for a
     * week wakes having burned no attempts, which is exactly what the wall-clock version got wrong.
     *
     * @param observations how many times we have looked and seen no movement
     * @param limit        the bound; {@code <= 0} means yield indefinitely
     */
    public static boolean eraYieldObservationsExhausted(final int observations, final int limit) {
        return limit > 0 && observations >= limit;
    }

    /**
     * Stamp the RCC.16 body header's per-message counter with the generation the message will be
     * encrypted at. Header layout: {@code [00 01][00 01][uint32 counter][mls_varint len]}.
     *
     * <p>That counter MUST equal {@code sender_data.generation}: Google Messages increments the two in
     * lockstep (captured from a peer — counter 00000000..00000004 alongside generations 0..4 across five
     * TEXT messages), and a Google Messages peer rejects a mismatch. It is NOT a content-type enum, which is what the
     * hardcoded {@code 2} in both our framing paths assumed; content type rides in the CPIM headers.
     *
     * <p>Framing is commonized in messaging2, which cannot know the generation — it lives in the
     * provider's sender ratchet and is only final at encrypt time — so messaging2 writes a placeholder
     * and the provider overwrites it here. Non-RCC.16 bodies are returned untouched (identity), and the
     * input is never mutated.
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
        /** Put the snapshot back — the conversation continues unharmed. */
        RESTORE_SNAPSHOT,
        /** No snapshot to restore: drop the dangling record so the next send RE-ESTABLISHES. */
        DROP_RECORD_AND_REESTABLISH
    }

    /**
     * A resync deletes the local group BEFORE it knows the external commit can be built or accepted.
     * If it then bails out early, the conversation is left with a record pointing at MISSING group
     * state — after which every send dies with "encrypt returned null" and never self-heals, because
     * the record still exists so establish is skipped. (Device-hit: a forced resync probe failed with
     * {@code MissingExternalPubExtension} and silently destroyed a live conversation.)
     *
     * <p>So a failure must ALWAYS leave a usable state: restore the snapshot when we have one, and
     * otherwise drop the record so the next send rebuilds the conversation from scratch.
     */
    public static ResyncFailureAction onResyncFailure(final boolean haveSnapshot) {
        return haveSnapshot
                ? ResyncFailureAction.RESTORE_SNAPSHOT
                : ResyncFailureAction.DROP_RECORD_AND_REESTABLISH;
    }

    /**
     * Has recovery run out of moves, leaving a choice only a PERSON can make?
     *
     * <p>Both conditions are required, and each one alone is a different situation with a different
     * remedy:
     *
     * <ul>
     *   <li><b>Budget exhausted, moment reachable</b> — ordinary backoff. The gap is an epoch gap we
     *       close by replaying commits; more time fixes it and the user should not be asked anything.</li>
     *   <li><b>Moment unreachable, budget remaining</b> — we still owe this conversation repair
     *       attempts. Asking now would interrupt a recovery that has not finished trying.</li>
     *   <li><b>Both</b> — we have tried every attempt we allow ourselves, and the thing we are waiting
     *       for is an era we cannot reach without a Welcome that only the peer or the server can send.
     *       There is no further automatic move, and the two remaining moves cost the user different
     *       things. That is the case this returns true for.</li>
     * </ul>
     *
     * <p>What the caller must then do is <b>ask</b>, not act: raise the choice, keep the conversation
     * readable-if-it-ever-becomes-readable, and change nothing about encryption until the user says
     * so. See {@link MlsHostAction.Kind#USER_ACTION_REQUIRED} for why this is not an automatic
     * downgrade and not silence.
     */
    public static boolean needsUserChoice(final boolean selfHealBudgetExhausted,
            final boolean awaitedMomentReachable) {
        return selfHealBudgetExhausted && !awaitedMomentReachable;
    }
}
