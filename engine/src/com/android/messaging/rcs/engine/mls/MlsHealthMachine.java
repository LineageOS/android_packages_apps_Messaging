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

import com.android.messaging.rcs.engine.mls.MlsAppMessage.Moment;

/**
 * The health state machine — §5.5's guarded entry point over §5.3's 127-pair table.
 *
 * <p>Rework items 3.1–3.6. Pure: no Android, no clock, no I/O. That is not an aesthetic choice —
 * §19.2-10 is explicit that there is <b>no clock read on the transition path at all</b>, so a
 * transition that consulted one would be wrong in a way no test of ours would catch. The current
 * {@link Moment} is passed IN.
 *
 * <h2>The guard order is the specification</h2>
 *
 * <p>§5.5, in order, and every step of it is load-bearing:
 *
 * <ol>
 *   <li>{@code cur == to} → a silent no-op that <b>falls through to the shared tail</b>. NOT an early
 *       return: it still runs {@code on_enter}, it emits NO telemetry, and it does NOT run
 *       {@code on_transition}. It returns the SENTINEL, not an edge.</li>
 *   <li>look up the edge</li>
 *   <li>illegal → reject, unless the escape hatch is on (it is not; item 3.6)</li>
 *   <li>telemetry <b>BEFORE</b> the mutation (Invariant 12)</li>
 *   <li>the mutation</li>
 *   <li>{@code on_transition} then {@code on_enter}, both <b>AFTER</b> it (Invariant 13)</li>
 * </ol>
 *
 * <p><b>Step 1 happening before step 2 is why the table's one self-loop is dead data.</b>
 * {@code OngoingEraAdvancement → OngoingEraAdvancement} is present in §5.3 and can never fire,
 * because {@code cur == to} is answered first. Get the order wrong and it becomes live.
 *
 * <p><b>The outcome is modelled as an {@link Outcome} plus a nullable edge, never as an edge alone.</b>
 * The sentinel is not a variant, and an implementation that maps it onto one mis-logs — §5.5 calls
 * that out specifically.
 */
public final class MlsHealthMachine {

    /** What a transition request did. */
    public enum Outcome {
        /** {@code cur == to}. The tail still ran; no edge, no telemetry. */
        NO_OP,
        /** The pair is in the table. {@link Decision#edge} is the edge. */
        LEGAL,
        /** The pair is not in the table and the escape hatch is off. Nothing changed. */
        ILLEGAL_REJECTED,
        /** Not reachable in shipped builds — see {@link #decide}. */
        ILLEGAL_ALLOWED
    }

    /** The result of {@link #decide}: what would happen, with no state touched. */
    public static final class Decision {
        public final Outcome outcome;
        /** The edge, or {@code null} for {@link Outcome#NO_OP} and {@link Outcome#ILLEGAL_REJECTED}. */
        public final MlsHealthEdge edge;

        Decision(final Outcome outcome, final MlsHealthEdge edge) {
            this.outcome = outcome;
            this.edge = edge;
        }

        public boolean changesState() { return outcome == Outcome.LEGAL || outcome == Outcome.ILLEGAL_ALLOWED; }
        /** Telemetry is emitted for a real transition only — never for a no-op (§5.5 step 6). */
        public boolean emitsTelemetry() { return changesState(); }

        @Override public String toString() {
            return outcome + (edge == null ? "" : "(" + edge.wireName + ")");
        }
    }

    /** The result of {@link #transition}: the decision, plus the record it produced. */
    public static final class Applied {
        public final Decision decision;
        /** The new record. The SAME instance when nothing changed. */
        public final MlsConversationRecord record;

        Applied(final Decision decision, final MlsConversationRecord record) {
            this.decision = decision;
            this.record = record;
        }
    }

    /**
     * Item 3.1 — the pure decision over the table. Touches nothing.
     *
     * <p>{@code allowIllegal} exists because 3.1 mandates the signature, and is wired to a
     * compile-time {@code false} at every call site (item 3.6: Google Messages ships the hatch off, and the
     * hatch KEEPS the illegal move rather than substituting a legal one, which makes an
     * operator-flippable version of it a corruption vector rather than a recovery lever — so it is
     * deliberately NOT a {@code debug.rcs.*} sysprop).
     */
    public static Decision decide(final int from, final int to, final boolean allowIllegal) {
        if (from == to) return new Decision(Outcome.NO_OP, null);
        final MlsHealthEdge e = MlsHealthEdge.forPair(from, to);
        if (e != null) return new Decision(Outcome.LEGAL, e);
        if (!allowIllegal) return new Decision(Outcome.ILLEGAL_REJECTED, null);
        return new Decision(Outcome.ILLEGAL_ALLOWED, MlsHealthEdge.INVALID_TRANSITION);
    }

    /**
     * The states a recovery lands FROM when arriving at Healthy — §5.10 Block A.
     *
     * <p><b>SIX states, and the mask is the authority: {@code 0x10d6} = {@code {1,2,4,6,7,12}}</b>
     * — the engine shifts that mask by the SOURCE state, under an entry guard that also requires
     * {@code to == Healthy(13)}.
     *
     * <p><b>{@code DoneEndMls(10)} is NOT one of them, and that is the trap.</b> Bit 10 is CLEAR in
     * {@code 0x10d6} — {@code {1,2,4,6,7,10,12}} would be {@code 0x14d6}. We carried the seven-state
     * reading in §5.10 and in this method for a while; it was a transcription of the mask, not
     * a reading of it, and it was wrong in every build checked. The same constant appears in
     * {@code get_group_status}'s HEALING rung,
     * expanded correctly there as {@code {1,2,4,6,7,12}} — an independent second site.
     *
     * <p><b>The divergence was REACHABLE, not theoretical.</b> {@code DoneEndMls → Healthy} is a
     * legal edge ({@code HealedAfterRemoteCommit}), so we stamped {@code recoveredAt} on a landing
     * where Google Messages leaves it alone. Since §22.1-39 that field is
     * {@link MlsPendingQueue.Admission#RESENT_FTD}'s comparand, so a spurious stamp moves G3's gate
     * FORWARD of Google Messages' and we admit fewer resends than it does — the same too-strict failure
     * §22.1-39 fixed, arriving through a different door.
     *
     * <p>A landing here is "a repair completed", not merely "we reached Healthy legally".
     */
    private static boolean isRecoveryLanding(final int from) {
        switch (from) {
            case MlsHealthStates.EPOCHADVANCEMENTREQUESTED:            // 1
            case MlsHealthStates.ONGOINGEPOCHADVANCEMENT:              // 2
            case MlsHealthStates.ONGOINGERAADVANCEMENT:                // 4
            case MlsHealthStates.SELFHEALFAILED:                       // 6
            case MlsHealthStates.ERAADVANCEMENTREQUESTED:              // 7
            case MlsHealthStates.ONGOINGERAADVANCEMENTFORREVIVE:       // 12
                return true;
            default:
                return false;
        }
    }

    /**
     * The four states Block B fires on — §5.10.
     *
     * <p><b>NOT 9 ({@code OngoingEndMls}) and NOT 11 ({@code OngoingReviveMls})</b>, even though both
     * are "ongoing" states and the symmetry is inviting. The four are the even ones: 2, 4, 12, 16.
     */
    private static boolean isBlockBTarget(final int to) {
        return to == MlsHealthStates.ONGOINGEPOCHADVANCEMENT              // 2
                || to == MlsHealthStates.ONGOINGERAADVANCEMENT            // 4
                || to == MlsHealthStates.ONGOINGERAADVANCEMENTFORREVIVE   // 12
                || to == MlsHealthStates.ONGOINGPHOENIXMODE;              // 16
    }

    /**
     * Items 3.2 and 3.4 — the guarded entry point, applied to a record.
     *
     * @param cur  the conversation's record
     * @param to   the requested status (a WIRE number — item 3.3; never an ordinal)
     * @param now  the group's current moment. Passed in because the transition path reads no clock.
     * @param allowIllegal always false in shipped builds; see {@link #decide}
     */
    public static Applied transition(final MlsConversationRecord cur, final int to,
            final Moment now, final boolean allowIllegal) {
        if (cur == null) return new Applied(new Decision(Outcome.ILLEGAL_REJECTED, null), null);
        final int from = cur.healthStatus;
        final Decision d = decide(from, to, allowIllegal);

        if (d.outcome == Outcome.ILLEGAL_REJECTED) {
            return new Applied(d, cur);                 // nothing changed
        }

        final MlsConversationRecord.Builder b = cur.toBuilder();

        if (d.changesState()) {
            // Step 7: the mutation. Telemetry (step 6) is the CALLER's to emit before this, which is
            // why Decision reports emitsTelemetry() rather than this method doing it — a pure
            // function cannot emit, and pushing the sink in here would put a port on the transition
            // path that §19.2-10 says does not belong there.
            b.healthStatus(to);
            // Step 9: on_transition, AFTER the mutation.
            if (to == MlsHealthStates.HEALTHY && isRecoveryLanding(from)) {
                // Block A: a RECOVERY landing in Healthy, not merely any arrival at Healthy.
                b.recoveredAt(now);
                b.storedStatusRequest(null);
            } else if (isBlockBTarget(to)) {
                b.storedStatusRequest(null);
            }
        }

        // Step 10: on_enter, which runs on the NO-OP path too — that is the whole point of the tail
        // not being an early return. It is gated on the DESTINATION being Healthy, so
        // transition(Healthy → Healthy) is not side-effect-free. §5.5 flags whether that is a latent
        // defect in Google Messages as NEEDS-CAPTURE; we copy the behaviour rather than quietly improving it,
        // because a divergence we invented is worse than one we inherited and documented.
        if (to == MlsHealthStates.HEALTHY) {
            b.lastHealthyMoment(now);
        }

        return new Applied(d, b.build());
    }

    /**
     * Item 3.5 — the ONE seed constructor.
     *
     * <p>Called from exactly two places, and the two are told apart deliberately:
     * {@link MlsHealthStates#INITIALIZING} on a storage {@code NotFound} (a group we have never
     * written), and {@link MlsHealthStates#HEALTHY} on a storage READ ERROR — because a record we
     * cannot read is not a group we have never seen, and seeding it {@code Initializing} would send
     * a working conversation through the whole recovery ladder on a transient read failure.
     *
     * @throws IllegalArgumentException on any seed outside {UNKNOWN, INITIALIZING, HEALTHY}
     */
    public static MlsConversationRecord newState(final String identity, final byte[] groupId,
            final String rcsGroupId, final String peerE164, final int seed) {
        if (seed != MlsHealthStates.UNKNOWN
                && seed != MlsHealthStates.INITIALIZING
                && seed != MlsHealthStates.HEALTHY) {
            throw new IllegalArgumentException("illegal seed status " + seed + " ("
                    + MlsHealthStates.name(seed) + ") — only Unknown, Initializing or Healthy");
        }
        return MlsConversationRecord.initial(identity, groupId, rcsGroupId, peerE164)
                .toBuilder().healthStatus(seed).build();
    }

    /**
     * Item 3.5 — the single documented table-bypassing write.
     *
     * <p>{@code → CannotHealDuringEndMls} from {@code OngoingEraAdvancement(4)} and
     * {@code EraAdvancementRequested(7)}. Those two pairs are deliberately <b>NOT</b> in the §5.3
     * table: adding them would make the bypass indistinguishable from an ordinary transition, and
     * the point of a documented escape is that it is visible.
     *
     * <p>This is also what our end-mls guards have been missing. They are pure refusals today —
     * they return and record nothing, so the wedged-in-downgrade condition is invisible the instant
     * the call returns and is recomputed from the engine on every subsequent trigger. Google Messages
     * PERSISTS state 15, which is what then drives the host's downgrade handling.
     *
     * @throws IllegalArgumentException if used from any state other than the two documented sources
     */
    public static MlsConversationRecord forceCannotHealDuringEndMls(
            final MlsConversationRecord cur, final String reason) {
        if (cur == null) return null;
        final int from = cur.healthStatus;
        if (from != MlsHealthStates.ONGOINGERAADVANCEMENT
                && from != MlsHealthStates.ERAADVANCEMENTREQUESTED) {
            throw new IllegalArgumentException("forceSet → CannotHealDuringEndMls is documented only "
                    + "from OngoingEraAdvancement(4) and EraAdvancementRequested(7), not from "
                    + MlsHealthStates.name(from) + "(" + from + ")");
        }
        return cur.toBuilder()
                .healthStatus(MlsHealthStates.CANNOTHEALDURINGENDMLS)
                .build();
    }

    private MlsHealthMachine() {}
}
