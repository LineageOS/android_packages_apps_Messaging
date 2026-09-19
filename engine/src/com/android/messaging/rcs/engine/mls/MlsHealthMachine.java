/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.engine.mls.MlsAppMessage.Moment;

/**
 * The health state machine: a guarded entry point over the {@link MlsHealthStates} table. Pure,
 * with no clock read on the transition path; the current {@link Moment} is passed in. The guard
 * order is the contract: a {@code cur == to} no-op still runs {@code on_enter}, then edge lookup,
 * illegal rejection, telemetry by the caller before the write, the write, and {@code on_transition}
 * then {@code on_enter} after it. See docs/mls/health-and-recovery.md.
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
        /** Not reachable in shipped builds; see {@link #decide}. */
        ILLEGAL_ALLOWED
    }

    /** The result of {@link #decide}: what would happen, with no state touched. */
    public static final class Decision {
        public final Outcome outcome;
        /** The edge, or {@code null} for {@code NO_OP} and {@code ILLEGAL_REJECTED}. */
        public final MlsHealthEdge edge;

        Decision(final Outcome outcome, final MlsHealthEdge edge) {
            this.outcome = outcome;
            this.edge = edge;
        }

        public boolean changesState() { return outcome == Outcome.LEGAL || outcome
                == Outcome.ILLEGAL_ALLOWED; }
        /** Telemetry is emitted for a real transition only, never for a no-op. */
        public boolean emitsTelemetry() { return changesState(); }

        @Override public String toString() {
            return outcome + (edge == null ? "" : "(" + edge.wireName + ")");
        }
    }

    /** The result of {@link #transition}: the decision, plus the record it produced. */
    public static final class Applied {
        public final Decision decision;
        /** The new record; the same instance when nothing changed. */
        public final MlsConversationRecord record;

        Applied(final Decision decision, final MlsConversationRecord record) {
            this.decision = decision;
            this.record = record;
        }
    }

    /**
     * The decision over the table; touches nothing. {@code allowIllegal} is {@code false} at every
     * call site and deliberately not configurable: it keeps the illegal move, so a switchable hatch
     * would be a corruption vector.
     */
    public static Decision decide(final int from, final int to, final boolean allowIllegal) {
        if (from == to) return new Decision(Outcome.NO_OP, null);
        final MlsHealthEdge e = MlsHealthEdge.forPair(from, to);
        if (e != null) return new Decision(Outcome.LEGAL, e);
        if (!allowIllegal) return new Decision(Outcome.ILLEGAL_REJECTED, null);
        return new Decision(Outcome.ILLEGAL_ALLOWED, MlsHealthEdge.INVALID_TRANSITION);
    }

    /**
     * Sources whose arrival at Healthy is a recovery landing: {1, 2, 4, 6, 7, 12}. Not
     * {@code DoneEndMls} (10), although that edge is legal: {@code recoveredAt} is the comparand of
     * {@link MlsPendingQueue.Admission#RESENT_FTD}, and a spurious stamp would admit fewer resends.
     */
    private static boolean isRecoveryLanding(final int from) {
        switch (from) {
            case MlsHealthStates.EPOCHADVANCEMENTREQUESTED:
            case MlsHealthStates.ONGOINGEPOCHADVANCEMENT:
            case MlsHealthStates.ONGOINGERAADVANCEMENT:
            case MlsHealthStates.SELFHEALFAILED:
            case MlsHealthStates.ERAADVANCEMENTREQUESTED:
            case MlsHealthStates.ONGOINGERAADVANCEMENTFORREVIVE:
                return true;
            default:
                return false;
        }
    }

    /** Targets that clear the stored status request: 2, 4, 12 and 16, not 9 or 11. */
    private static boolean isBlockBTarget(final int to) {
        return to == MlsHealthStates.ONGOINGEPOCHADVANCEMENT
                || to == MlsHealthStates.ONGOINGERAADVANCEMENT
                || to == MlsHealthStates.ONGOINGERAADVANCEMENTFORREVIVE
                || to == MlsHealthStates.ONGOINGPHOENIXMODE;
    }

    /**
     * The guarded entry point, applied to a record.
     *
     * @param to the requested status as a wire number, never an ordinal
     * @param now the group's current moment
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
            // The caller emits telemetry before this write; a pure function cannot.
            b.healthStatus(to);
            // on_transition, after the mutation.
            if (to == MlsHealthStates.HEALTHY && isRecoveryLanding(from)) {
                b.recoveredAt(now);
                b.storedStatusRequest(null);
            } else if (isBlockBTarget(to)) {
                b.storedStatusRequest(null);
            }
        }

        // on_enter runs on the no-op path too, so Healthy -> Healthy restamps lastHealthyMoment,
        // matching other clients.
        if (to == MlsHealthStates.HEALTHY) {
            b.lastHealthyMoment(now);
        }

        return new Applied(d, b.build());
    }

    /**
     * The one seed constructor. Storage "not found" seeds {@link MlsHealthStates#INITIALIZING}; a
     * storage read error seeds {@link MlsHealthStates#HEALTHY}, so a transient read failure does
     * not send a working conversation down the recovery ladder.
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
     * The single table-bypassing write: to {@code CannotHealDuringEndMls} from
     * {@code OngoingEraAdvancement} (4) or {@code EraAdvancementRequested} (7). Those pairs are
     * kept out of the table so the bypass stays visible. The persisted state 15 drives the host's
     * downgrade handling.
     *
     * @throws IllegalArgumentException from any other state
     */
    public static MlsConversationRecord forceCannotHealDuringEndMls(
            final MlsConversationRecord cur, final String reason) {
        if (cur == null) return null;
        final int from = cur.healthStatus;
        if (from != MlsHealthStates.ONGOINGERAADVANCEMENT
                && from != MlsHealthStates.ERAADVANCEMENTREQUESTED) {
            throw new IllegalArgumentException(
                    "forceSet → CannotHealDuringEndMls is documented only "
                    + "from OngoingEraAdvancement(4) and EraAdvancementRequested(7), not from "
                    + MlsHealthStates.name(from) + "(" + from + ")");
        }
        return cur.toBuilder()
                .healthStatus(MlsHealthStates.CANNOTHEALDURINGENDMLS)
                .build();
    }

    private MlsHealthMachine() {}
}
